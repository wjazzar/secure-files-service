package com.praxedo.securefiles.application.file.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The number of pending files — not yet terminal, all owners together — as
 * upload admission reads it: at most {@code maxAge} old, and never worth
 * waiting for.
 *
 * <p><strong>The strategy, in three cases.</strong>
 * <ul>
 *   <li>The last measure is fresh: it is returned as is. No database.</li>
 *   <li>It is stale, and nobody is refreshing it: this request refreshes it,
 *       and returns the new value.</li>
 *   <li>It is stale, and another request is already refreshing it: this
 *       request does <em>not</em> wait. It returns the stale measure at once.</li>
 * </ul>
 * The third case is the point. A count is a query, and a query needs a pooled
 * connection: under a burst, every request waiting on the same refresh would
 * queue for one, competing with the workers that empty the queue. A slightly
 * stale answer costs nothing, because the bound it feeds is approximate anyway
 * (see {@link UploadAdmission}).
 *
 * <p>Before the very first measure, a request that loses that race reads zero:
 * the bound errs on the side of accepting, once, for one refresh interval.
 */
final class PendingFileCountCache {

    private static final Snapshot NEVER_MEASURED = new Snapshot(0, Instant.MIN);

    private final LongSupplier countPendingFiles;
    private final Duration maxAge;
    private final Clock clock;

    /** Held by the one request refreshing the count; the others never wait for it. */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Snapshot latest = NEVER_MEASURED;

    PendingFileCountCache(LongSupplier countPendingFiles, Duration maxAge, Clock clock) {
        this.countPendingFiles = Objects.requireNonNull(countPendingFiles, "count of pending files");
        this.maxAge = Objects.requireNonNull(maxAge, "max age");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    long current() {
        Snapshot snapshot = latest;
        if (isFresh(snapshot)) {
            return snapshot.count();
        }
        if (!refreshLock.tryLock()) {
            return snapshot.count();    // someone else is refreshing: never wait for it
        }
        try {
            return refreshIfStillStale().count();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Called with the lock held. The measure is read again first: the previous
     * holder may have refreshed it between our check and our taking the lock.
     */
    private Snapshot refreshIfStillStale() {
        Snapshot snapshot = latest;
        if (isFresh(snapshot)) {
            return snapshot;
        }
        long count = countPendingFiles.getAsLong();
        Snapshot measured = new Snapshot(count, clock.instant());
        latest = measured;
        return measured;
    }

    private boolean isFresh(Snapshot snapshot) {
        return clock.instant().isBefore(snapshot.measuredAt().plus(maxAge));
    }

    /** A count, and when it was taken. Immutable, so it is published whole or not at all. */
    private record Snapshot(long count, Instant measuredAt) {
    }
}
