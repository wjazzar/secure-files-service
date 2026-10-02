package com.praxedo.securefiles.application.file.model;

import java.util.Optional;

/**
 * A half-open-free, inclusive byte range, as HTTP expresses it.
 *
 * <p>Only a single range is modelled. Multi-range responses require a
 * multipart body, which would mean assembling parts in memory — exactly what
 * rule B-1 forbids. A request for several ranges is ignored, and the whole
 * object is served — which RFC 9110 allows.
 *
 * @param firstByte     zero-based index of the first byte to return
 * @param lastByte      inclusive index of the last byte, or empty for "to the end"
 */
public record ByteRange(long firstByte, Optional<Long> lastByte) {

    /** The whole object. */
    public static final ByteRange WHOLE_OBJECT = new ByteRange(0, Optional.empty());

    public ByteRange {
        if (firstByte < 0) {
            throw new IllegalArgumentException("A range cannot start before the first byte");
        }
        if (lastByte.isPresent() && lastByte.get() < firstByte) {
            throw new IllegalArgumentException("A range cannot end before it starts");
        }
    }

    public static ByteRange from(long firstByte) {
        return new ByteRange(firstByte, Optional.empty());
    }

    public static ByteRange of(long firstByte, long lastByte) {
        return new ByteRange(firstByte, Optional.of(lastByte));
    }

    public boolean isWholeObject() {
        return firstByte == 0 && lastByte.isEmpty();
    }

    /** The {@code Range} header value this range corresponds to. */
    public String asHttpHeader() {
        return "bytes=" + firstByte + "-" + lastByte.map(String::valueOf).orElse("");
    }
}
