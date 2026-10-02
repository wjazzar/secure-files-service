package com.praxedo.securefiles.application.file.model;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The bounds the analysis works within — every one of them externalised.
 *
 * <p>How many analyses run at once is not among them: that is the number of
 * analysis loops, which belongs to the scheduling adapter.
 *
 * @param lease          how long a claim holds a file before anyone may take it
 *                       over, in proportion to its size
 * @param promotionLease how long the follow-up copy may hold it — longer, because
 *                       copying 500 MB takes longer than scanning them
 * @param maxAttempts    after this many attempts, a file is given up on for good
 * @param backoffBase    the delay before the first retry; it doubles each time
 * @param backoffMax     the ceiling of that doubling
 */
public record WorkerSettings(LeaseTerms lease, LeaseTerms promotionLease,
                             int maxAttempts, Duration backoffBase, Duration backoffMax) {

    public WorkerSettings {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(promotionLease, "promotion lease");
        Objects.requireNonNull(backoffBase, "backoff base");
        Objects.requireNonNull(backoffMax, "backoff max");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("A worker needs at least one attempt");
        }
    }

    /**
     * Exponential backoff with ±20 % jitter. The jitter is what keeps a batch
     * of files that failed together — an antivirus restart — from all coming
     * back in the same second.
     */
    public Duration retryDelay(int attemptsSoFar) {
        long base = backoffBase.toMillis() << Math.min(Math.max(attemptsSoFar - 1, 0), 20);
        long capped = Math.min(base, backoffMax.toMillis());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (capped * jitter));
    }
}
