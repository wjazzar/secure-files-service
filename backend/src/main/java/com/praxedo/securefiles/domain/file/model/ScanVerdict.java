package com.praxedo.securefiles.domain.file.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.praxedo.securefiles.domain.file.valueobject.Sha256;

/**
 * The outcome of one analysis, bound to the exact bytes that were analysed.
 *
 * <p>{@link #scannedContent()} is what turns this from a claim into an
 * attestation. Without it, the record says "a file was inspected and found
 * clean"; with it, it says "<em>this content</em> was inspected and found
 * clean". Promotion checks the second statement, which closes the scenario
 * where the stored object changed between the analysis and the copy.
 *
 * <p>{@link #signatureVersion()} is mandatory for a {@link ScanResult#CLEAN}
 * verdict, and for the same family of reasons: "clean according to which
 * signature database, and when?" is the first question asked after an
 * incident, and the only moment it can be answered cheaply is now.
 *
 * @param result           what the engine concluded
 * @param threatName       name of the detected threat; {@code null} unless infected, and
 *                         may legitimately be {@code null} when the engine's answer could
 *                         not be parsed — the status still blocks the file
 * @param engine           engine name, e.g. {@code ClamAV}
 * @param engineVersion    engine version; {@code null} when the engine did not say
 * @param signatureVersion signature database version; required for a clean verdict
 * @param scannedContent   digest of the bytes that were actually inspected
 * @param scannedAt        when the verdict was obtained
 * @param duration         how long the analysis took
 */
public record ScanVerdict(
        ScanResult result,
        String threatName,
        String engine,
        String engineVersion,
        String signatureVersion,
        Sha256 scannedContent,
        Instant scannedAt,
        Duration duration) {

    public ScanVerdict {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(scannedContent, "scanned content digest");
        Objects.requireNonNull(scannedAt, "scanned at");
        Objects.requireNonNull(duration, "duration");

        if (engine.isBlank()) {
            throw new IllegalArgumentException("The engine that produced a verdict must be named");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("An analysis cannot take a negative amount of time");
        }
        if (result == ScanResult.CLEAN && (signatureVersion == null || signatureVersion.isBlank())) {
            throw new IllegalArgumentException(
                    "A clean verdict must record the signature database version it was based on");
        }
    }

    /**
     * Whether this verdict authorises serving the given content.
     *
     * <p>Both halves matter: a clean verdict for <em>other</em> bytes
     * authorises nothing.
     */
    public boolean attestsCleanFor(Sha256 content) {
        return result == ScanResult.CLEAN && scannedContent.equals(content);
    }

    public Optional<String> threat() {
        return Optional.ofNullable(threatName);
    }
}
