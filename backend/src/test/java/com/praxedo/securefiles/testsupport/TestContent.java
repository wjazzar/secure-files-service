package com.praxedo.securefiles.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * File content for tests — produced on the fly, never materialised.
 *
 * <p>A 500 MB test file written to disk would contradict the rule the test is
 * there to prove (nothing on the local file system), and one held in a
 * {@code byte[]} would contradict the other (nothing in memory). The content
 * here is a deterministic function of the position, so its digest is known
 * without ever storing it.
 */
public final class TestContent {

    private TestContent() {
    }

    /** {@code size} bytes, the same ones every time. */
    public static InputStream generated(long size) {
        return new InputStream() {
            private long produced;

            @Override
            public int read() {
                return produced < size ? byteAt(produced++) : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (produced >= size) {
                    return -1;
                }
                int count = (int) Math.min(length, size - produced);
                for (int index = 0; index < count; index++) {
                    buffer[offset + index] = (byte) byteAt(produced + index);
                }
                produced += count;
                return count;
            }
        };
    }

    /** The digest of {@link #generated(long)}, computed by streaming it once. */
    public static String digestOfGenerated(long size) {
        return digestOf(generated(size));
    }

    /** Reads a stream to its end and returns its SHA-256, holding nothing. */
    public static String digestOf(InputStream content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digesting = new DigestInputStream(content, sha256)) {
                byte[] buffer = new byte[64 * 1024];
                while (digesting.read(buffer) >= 0) {
                    // the digest is the point
                }
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A printable, position-dependent pattern: easy to eyeball in a failed range test. */
    private static int byteAt(long position) {
        return 'a' + (int) (position % 26);
    }
}
