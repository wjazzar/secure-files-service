package com.praxedo.securefiles.application.file.model;

import java.time.Duration;
import java.util.Objects;

/**
 * The bounds an upload must fit in — all of them externalised, none of them
 * written in the code (testability requirement TST-4).
 *
 * @param maxSizeBytes    admission limit; kept equal to what the antivirus is
 *                        configured to analyse, so that every accepted file is
 *                        analysable
 * @param maxPendingFiles      above this many files with work ahead, uploads
 *                             are refused with {@code 429}
 * @param maxConcurrentUploads uploads this node receives at once; one more is
 *                             refused with {@code 429}, before its body is read
 * @param pendingCountMaxAge   how old the count of pending files may be when
 *                             admitting an upload; zero reads it every time
 * @param idempotencyTtl       how long a key replays its file
 * @param transferDeadline     how long the body of an upload may take to
 *                             arrive, in proportion to its declared size: the
 *                             slowest transfer the service accepts. Without it,
 *                             a client trickling its body holds its permit on
 *                             the node for as long as it pleases
 */
public record UploadLimits(long maxSizeBytes, long maxPendingFiles, int maxConcurrentUploads,
                           Duration pendingCountMaxAge, Duration idempotencyTtl, LeaseTerms transferDeadline) {

    public UploadLimits {
        Objects.requireNonNull(pendingCountMaxAge, "pending count max age");
        Objects.requireNonNull(idempotencyTtl, "idempotency ttl");
        Objects.requireNonNull(transferDeadline, "transfer deadline");
        if (maxSizeBytes <= 0 || maxPendingFiles <= 0 || maxConcurrentUploads <= 0
                || pendingCountMaxAge.isNegative() || idempotencyTtl.isNegative() || idempotencyTtl.isZero()) {
            throw new IllegalArgumentException("Upload limits must all be positive");
        }
    }
}
