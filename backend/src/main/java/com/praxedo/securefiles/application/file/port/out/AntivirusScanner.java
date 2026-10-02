package com.praxedo.securefiles.application.file.port.out;

import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.domain.file.model.ScanResult;

/**
 * The antivirus, reached through its API.
 *
 * <p>What it returns is deliberately narrower than a verdict: the engine says
 * what it concluded and which signatures it used, but it does not know
 * <em>which bytes</em> it was given. The worker hashes the stream it hands over,
 * and it is the worker that binds the conclusion to that digest. An adapter
 * cannot, even by mistake, attest content it never measured.
 *
 * <p>A technical failure — the engine is down, too slow, answers something
 * unreadable — is <strong>never</strong> an outcome. It is an exception,
 * {@link ScannerUnavailableException}, so that no code path can confuse
 * "nothing was concluded" with a conclusion.
 */
public interface AntivirusScanner {

    /**
     * Streams {@code sizeBytes} bytes to the engine and returns what it concluded.
     *
     * @throws ScannerUnavailableException for every failure that says nothing about the content
     */
    ScanOutcome scan(InputStream content, long sizeBytes);

    /**
     * The health gate: when this answers {@code false}, the worker does not
     * claim any work — so an antivirus outage consumes no attempt and ends with
     * no file wrongly given up on.
     */
    boolean isAvailable();

    /**
     * What the engine concluded.
     *
     * @param detail           the threat name, or the engine's explanation of why it could not
     *                         conclude; {@code null} for a clean result
     * @param signatureVersion required for a clean result: "clean according to which signatures?"
     */
    record ScanOutcome(ScanResult result, String detail, String engine, String engineVersion,
                       String signatureVersion, Duration duration) {

        public ScanOutcome {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(duration, "duration");
        }
    }
}
