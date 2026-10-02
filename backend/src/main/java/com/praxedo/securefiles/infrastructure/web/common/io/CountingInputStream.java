package com.praxedo.securefiles.infrastructure.web.common.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Counts the bytes that went through, for the throughput metrics — and holds none of them. */
public final class CountingInputStream extends FilterInputStream {

    private long count;

    public CountingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int read = super.read();
        if (read >= 0) {
            count++;
        }
        return read;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            count += read;
        }
        return read;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        count += skipped;
        return skipped;
    }

    public long count() {
        return count;
    }
}
