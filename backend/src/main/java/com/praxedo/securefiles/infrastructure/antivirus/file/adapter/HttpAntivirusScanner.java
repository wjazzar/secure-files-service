package com.praxedo.securefiles.infrastructure.antivirus.file.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.domain.file.model.ScanResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The antivirus, reached through its HTTP API — ClamAV behind
 * {@code clamav-rest}, see {@code infra/README.md} §3.
 *
 * <p><strong>The translation table is the security-relevant part</strong>, and
 * every line of it was measured against the running engine:
 * <table>
 *   <tr><th>Answer</th><th>Meaning</th></tr>
 *   <tr><td>{@code 200}</td><td>clean</td></tr>
 *   <tr><td>{@code 406}, a threat name</td><td>infected</td></tr>
 *   <tr><td>{@code 406}, {@code Heuristics.Limits.Exceeded…}</td><td><strong>unscannable</strong> —
 *       with {@code AlertExceedsMax}, a limit reached is reported as a detection.
 *       Calling it a threat would be wrong; calling it clean would be dangerous</td></tr>
 *   <tr><td>{@code 412}</td><td>unscannable: the engine could not parse the content</td></tr>
 *   <tr><td>{@code 413}</td><td><strong>a failure, not a verdict</strong> — the wrapper also
 *       answers it when the stream broke, and the service never sends more than
 *       the engine accepts</td></tr>
 *   <tr><td>anything else, a timeout, a broken connection</td><td>a failure</td></tr>
 * </table>
 *
 * <p>The scan body announces {@code application/json} but is not JSON (it is
 * the default rendering of a Go struct: {@code {FOUND Eicar-Test-Signature  406}}).
 * The status code is therefore what counts; the body is read as text, only for
 * the threat name — and a {@code 406} whose body cannot be read is recorded as
 * infected, the safe direction.
 */
public class HttpAntivirusScanner implements AntivirusScanner {

    private static final String ENGINE = "ClamAV";
    private static final int CLEAN = 200;
    private static final int THREAT_OR_LIMIT = 406;
    private static final int UNPARSEABLE = 412;
    private static final String LIMITS_EXCEEDED = "Heuristics.Limits.Exceeded";
    /** Scan answers are a few dozen bytes; anything longer is not an answer. */
    private static final int MAX_ANSWER_BYTES = 4 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient scanClient;
    private final RestClient healthClient;
    private final Duration versionCacheTtl;
    private final Clock clock;
    private final AtomicReference<CachedVersion> version = new AtomicReference<>();

    public HttpAntivirusScanner(RestClient scanClient, RestClient healthClient, Duration versionCacheTtl, Clock clock) {
        this.scanClient = scanClient;
        this.healthClient = healthClient;
        this.versionCacheTtl = versionCacheTtl;
        this.clock = clock;
    }

    @Override
    public ScanOutcome scan(InputStream content, long sizeBytes) {
        // Read before the scan, so the recorded signatures are at most one cache
        // period older than the ones that did the analysis — never newer.
        EngineVersion engine = engineVersion();
        Instant start = clock.instant();
        try {
            return scanClient.post()
                    .uri("/scanHandlerBody")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(sizeBytes)
                    .body((StreamingHttpOutputMessage.Body) out -> content.transferTo(out))
                    .exchange((request, response) -> translate(
                            response.getStatusCode(), readAnswer(response.getBody()),
                            engine, Duration.between(start, clock.instant())), true);
        } catch (RestClientException failure) {
            throw new ScannerUnavailableException("The antivirus could not be reached or did not answer", failure);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpStatusCode status = healthClient.get().uri("/").retrieve().toBodilessEntity().getStatusCode();
            return status.is2xxSuccessful();
        } catch (RestClientException unavailable) {
            return false;
        }
    }

    static ScanOutcome translate(HttpStatusCode status, String answer, EngineVersion engine, Duration duration) {
        int code = status.value();
        if (code == CLEAN) {
            return outcome(ScanResult.CLEAN, null, engine, duration);
        }
        if (code == THREAT_OR_LIMIT) {
            String detected = detectedName(answer);
            if (detected != null && detected.startsWith(LIMITS_EXCEEDED)) {
                return outcome(ScanResult.UNSCANNABLE, detected, engine, duration);
            }
            return outcome(ScanResult.INFECTED, detected, engine, duration);
        }
        if (code == UNPARSEABLE) {
            return outcome(ScanResult.UNSCANNABLE, "the engine could not parse the content", engine, duration);
        }
        throw new ScannerUnavailableException("The antivirus answered " + code + ", which is not a verdict");
    }

    /**
     * {@code {FOUND Eicar-Test-Signature  406}} → {@code Eicar-Test-Signature}.
     *
     * @return {@code null} when the answer does not have the expected shape
     */
    static String detectedName(String answer) {
        if (answer == null) {
            return null;
        }
        String[] tokens = answer.strip().replace("{", " ").replace("}", " ").strip().split("\\s+");
        if (tokens.length < 3 || !"FOUND".equals(tokens[0])) {
            return null;
        }
        return String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length - 1));
    }

    private static ScanOutcome outcome(ScanResult result, String detail, EngineVersion engine, Duration duration) {
        return new ScanOutcome(result, detail, ENGINE, engine.engine(), engine.signatures(), duration);
    }

    private EngineVersion engineVersion() {
        CachedVersion cached = version.get();
        if (cached != null && cached.fetchedAt().plus(versionCacheTtl).isAfter(clock.instant())) {
            return cached.value();
        }
        try {
            JsonNode answer = JSON.readTree(healthClient.get().uri("/version").retrieve().body(String.class));
            EngineVersion fresh = new EngineVersion(
                    answer.path("Clamav").asString(null), answer.path("Signature").asString(null));
            if (fresh.signatures() == null || fresh.signatures().isBlank()) {
                // Without it, a clean verdict cannot be recorded at all.
                throw new ScannerUnavailableException("The antivirus did not report its signature version");
            }
            version.set(new CachedVersion(fresh, clock.instant()));
            return fresh;
        } catch (RestClientException | tools.jackson.core.JacksonException failure) {
            throw new ScannerUnavailableException("The antivirus version could not be read", failure);
        }
    }

    private static String readAnswer(InputStream body) {
        try (body) {
            return new String(body.readNBytes(MAX_ANSWER_BYTES), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Engine and signature versions, as the engine reported them. */
    record EngineVersion(String engine, String signatures) {
    }

    private record CachedVersion(EngineVersion value, Instant fetchedAt) {
    }
}
