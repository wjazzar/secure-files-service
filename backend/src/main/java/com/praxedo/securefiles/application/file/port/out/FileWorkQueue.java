package com.praxedo.securefiles.application.file.port.out;

import java.time.Duration;
import java.util.Optional;

import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;

/**
 * The work queue — which is the same table as the catalogue, written with very
 * different statements.
 *
 * <p><strong>Every method here is conditional and returns whether it won.</strong>
 * {@code false} is not an error: it means another worker owns the work now, and
 * the caller must simply stop. Concurrency is settled by the database, not by
 * catching exceptions.
 *
 * <p><strong>Time comes from the database.</strong> Leases expire against
 * {@code clock_timestamp()}, never against a node's clock: with several
 * workers, a skewed clock would let one declare a lease expired while the work
 * is still running.
 *
 * <p><strong>A database that cannot be reached</strong> — no connection within
 * the pool's timeout, connection lost, query timed out — surfaces as a
 * {@link com.praxedo.securefiles.application.file.exception.WorkQueueUnavailableException}:
 * transient, and never a statement about the file.
 */
public interface FileWorkQueue {

    /**
     * Takes the next due file, atomically: selection, mutual exclusion
     * ({@code FOR UPDATE SKIP LOCKED}), lease and attempt count in one
     * statement.
     *
     * <p>The attempt is counted here rather than on failure, so that a worker
     * that dies mid-analysis still consumes one — which is what bounds a file
     * that kills its reader.
     *
     * @param lease       sized by the database from the claimed file's size: the
     *                    file is only known once claimed
     * @param maxAttempts files that already reached it are left alone, for good
     * @return the claimed file, lease included, or empty when nothing is due
     */
    Optional<StoredFile> claimNextDue(String workerId, LeaseToken token, LeaseTerms lease, int maxAttempts);

    /**
     * Writes a verdict-driven transition, provided this claim still owns the
     * work.
     *
     * @param claim the token of <em>this</em> claim — not the worker id: a
     *              frozen worker that re-claimed the same file must not be able
     *              to overwrite the fresh verdict with its stale one
     * @return false when the claim lost the work (expired lease, taken over)
     */
    boolean writeVerdict(StoredFile after, LeaseToken claim, Duration promotionLease);

    /** {@code PROMOTING -> AVAILABLE}, the compare-and-set that makes a file servable. */
    boolean markAvailable(StoredFile after, LeaseToken claim);

    /** A technical failure: back to the queue after {@code retryIn}, or final. */
    boolean writeTechnicalFailure(StoredFile after, LeaseToken claim, Duration retryIn, String error);

    /** Clean shutdown: the work goes back immediately, without the attempt. */
    boolean releaseOnShutdown(StoredFile after, LeaseToken claim);

    /**
     * Puts work whose lease expired back in the queue, with exponential backoff
     * and jitter, or fails it for good when attempts are exhausted.
     *
     * <p>Safe to run on every node at once: it is a conditional update, so the
     * nodes simply do not collide. That is why no distributed lock is needed.
     *
     * @return how many files were reclaimed
     */
    int reclaimExpiredLeases(int maxAttempts, Duration baseBackoff, Duration maxBackoff);
}
