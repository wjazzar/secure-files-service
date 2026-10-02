package com.praxedo.securefiles.infrastructure.antivirus.file.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.domain.file.model.ScanResult;

/**
 * Deterministic capacity-test adapter that immediately returns a clean verdict.
 *
 * <p>It deliberately consumes the whole stream. The worker therefore still
 * reads the quarantined S3 object and computes its SHA-256, and promotion still
 * copies and verifies the object. Only the external antivirus call is removed,
 * which makes the comparison with the HTTP engine meaningful.
 */
public final class InstantCleanAntivirusScanner implements AntivirusScanner {

    private static final String ENGINE = "capacity-stub";
    private static final String VERSION = "instant-clean-v1";

    private final Clock clock;

    public InstantCleanAntivirusScanner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ScanOutcome scan(InputStream content, long sizeBytes) {
        Instant start = clock.instant();
        try {
            content.transferTo(OutputStream.nullOutputStream());
        } catch (IOException failure) {
            throw new ScannerUnavailableException("The capacity stub could not consume the content", failure);
        }
        return new ScanOutcome(ScanResult.CLEAN, null, ENGINE, VERSION, VERSION,
                Duration.between(start, clock.instant()));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
