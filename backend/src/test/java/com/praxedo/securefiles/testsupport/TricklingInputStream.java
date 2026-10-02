package com.praxedo.securefiles.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * A peer that never quite goes silent: a few bytes per read, and the clock
 * moves on before each one. No socket timeout would ever fire on it.
 */
public final class TricklingInputStream extends InputStream {

    private final InputStream content;
    private final int bytesPerRead;
    private final SettableClock clock;
    private final Duration pauseBeforeEachRead;

    public TricklingInputStream(InputStream content, int bytesPerRead, SettableClock clock,
                                Duration pauseBeforeEachRead) {
        this.content = content;
        this.bytesPerRead = bytesPerRead;
        this.clock = clock;
        this.pauseBeforeEachRead = pauseBeforeEachRead;
    }

    @Override
    public int read() throws IOException {
        clock.advance(pauseBeforeEachRead);
        return content.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        clock.advance(pauseBeforeEachRead);
        return content.read(buffer, offset, Math.min(length, bytesPerRead));
    }

    @Override
    public void close() throws IOException {
        content.close();
    }
}
