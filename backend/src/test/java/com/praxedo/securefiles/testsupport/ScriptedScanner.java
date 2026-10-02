package com.praxedo.securefiles.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;

import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.domain.file.model.ScanResult;

/**
 * An antivirus that says what the test tells it to — after reading the whole
 * stream, as the real one does, so that the digest taken on the way is complete.
 */
public final class ScriptedScanner implements AntivirusScanner {

    public boolean available = true;
    public ScanResult next = ScanResult.CLEAN;
    public String detail;
    public boolean failing;
    public int scans;
    /** Runs while the engine is "analysing": where a test injects a concurrent event. */
    public Runnable duringScan = () -> { };

    @Override
    public ScanOutcome scan(InputStream content, long sizeBytes) {
        scans++;
        try {
            content.transferTo(OutputStreamSink.INSTANCE);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        duringScan.run();
        if (failing) {
            throw new ScannerUnavailableException("engine timed out");
        }
        return new ScanOutcome(next, detail, "ClamAV", "1.4.6", "28098", Duration.ofMillis(5));
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int value) {
            // discarded: only reading matters
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            // discarded
        }
    }
}
