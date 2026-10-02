package com.praxedo.securefiles.application.file.model;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a worker may hold a file: a floor, plus time in proportion to its size.
 *
 * <p>The same terms bound an upload's transfer ({@link UploadLimits}): an
 * upload holds its permit on the node the way a worker holds its file, and
 * for the same reason neither may keep it for ever.
 *
 * <p>A single fixed lease had to fit the largest file — ten minutes for 500 MB.
 * A 2 MiB file whose worker died, or could not reach the database to record
 * its verdict, then waited those ten minutes too before the reaper returned
 * it: the capacity campaign {@code scale-1cpu-8w} left three files exactly
 * there. In proportion, a small file's lease runs out in seconds and the
 * largest keep the time they need.
 *
 * @param minimum     the lease of an empty file: claiming it, reaching the
 *                    engine, recording the outcome
 * @param perMebibyte added for each MiB of content
 */
public record LeaseTerms(Duration minimum, Duration perMebibyte) {

    private static final double MEBIBYTE = 1024.0 * 1024.0;

    public LeaseTerms {
        Objects.requireNonNull(minimum, "lease minimum");
        Objects.requireNonNull(perMebibyte, "lease per mebibyte");
        if (minimum.isNegative() || minimum.isZero() || perMebibyte.isNegative()) {
            throw new IllegalArgumentException("A lease needs a positive minimum and a non-negative rate");
        }
    }

    public Duration forSize(long sizeBytes) {
        return minimum.plusNanos((long) (perMebibyte.toNanos() * (sizeBytes / MEBIBYTE)));
    }
}
