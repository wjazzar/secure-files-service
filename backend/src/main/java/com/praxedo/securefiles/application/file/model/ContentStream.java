package com.praxedo.securefiles.application.file.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * An open object, or a slice of one — to be streamed, never loaded.
 *
 * @param content     the bytes, still on the wire; the caller closes it
 * @param length      how many bytes {@code content} will yield
 * @param totalLength the size of the whole object, for {@code Content-Range}
 * @param firstByte   index of the first byte of {@code content} in the object
 */
public record ContentStream(InputStream content, long length, long totalLength, long firstByte)
        implements AutoCloseable {

    public ContentStream {
        Objects.requireNonNull(content, "content");
        if (length < 0 || totalLength < 0 || firstByte < 0 || firstByte + length > totalLength) {
            throw new IllegalArgumentException("A content stream must describe a slice of its object");
        }
    }

    public boolean isPartial() {
        return length != totalLength;
    }

    public long lastByte() {
        return firstByte + length - 1;
    }

    @Override
    public void close() {
        try {
            content.close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
