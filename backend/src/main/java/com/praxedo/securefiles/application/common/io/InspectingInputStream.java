package com.praxedo.securefiles.application.common.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.praxedo.securefiles.domain.file.valueobject.Sha256;

/**
 * Counts, hashes and caps a stream as it flows — in the same single pass that
 * writes it to storage.
 *
 * <p>Used twice, for the same reason: by the upload, which hashes what it
 * receives, and by the worker, which hashes what it analyses and what it
 * promotes. The attestation is only worth something if both digests are taken
 * the same way.
 *
 * <p>This is how rules B-1 and B-4 are kept at once: the content is never held
 * (every byte is seen exactly once, on its way through), and the size is
 * checked <em>while reading</em> rather than trusted from {@code Content-Length}.
 * A body longer than announced is cut off at the first extra byte; a shorter
 * one is visible in {@link #count()} when the stream ends.
 *
 * <p>Mark and reset are refused: replaying bytes would hash them twice. The
 * caller that needs to peek wraps this stream in a buffer, which replays from
 * its own copy.
 */
public final class InspectingInputStream extends FilterInputStream {

    private final MessageDigest sha256;
    private final long ceiling;
    private long count;
    private boolean exceeded;

    public InspectingInputStream(InputStream in, long ceiling) {
        super(in);
        this.ceiling = ceiling;
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", impossible);
        }
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            advance(1);
            sha256.update((byte) value);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            advance(read);
            sha256.update(buffer, offset, read);
        }
        return read;
    }

    /** Skipping would let bytes through unhashed: they are read instead. */
    @Override
    public long skip(long wanted) throws IOException {
        byte[] discard = new byte[8 * 1024];
        long skipped = 0;
        while (skipped < wanted) {
            int read = read(discard, 0, (int) Math.min(discard.length, wanted - skipped));
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readLimit) {
        // Refused, see markSupported().
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("An inspected stream cannot be replayed");
    }

    private void advance(int read) throws IOException {
        count += read;
        if (count > ceiling) {
            exceeded = true;
            throw new IOException("The body is longer than its declared length");
        }
    }

    public long count() {
        return count;
    }

    /** Whether the body tried to go past the length it declared. */
    public boolean exceeded() {
        return exceeded;
    }

    /** Only meaningful once the stream has been read to its end. */
    public Sha256 digest() {
        return new Sha256(HexFormat.of().formatHex(sha256.digest()));
    }
}
