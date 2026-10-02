package com.praxedo.securefiles.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.praxedo.securefiles.application.file.exception.WorkQueueUnavailableException;
import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.Lease;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;

/**
 * The work queue in memory, with the same rule as the real one: every write is
 * conditional on the lease token of the claim, and simply loses when the token
 * no longer matches.
 *
 * <p>That rule is the only thing about the queue the worker depends on, which is
 * why a fake is honest here. The real SQL — mutual exclusion, clock, backoff —
 * is tested on PostgreSQL in {@code FilePersistenceTest}.
 */
public final class InMemoryWorkQueue implements FileWorkQueue {

    private final Map<FileId, StoredFile> files = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private int unreachableClaims;
    private int unreachableWrites;

    public void add(StoredFile file) {
        files.put(file.id(), file);
    }

    public StoredFile current(FileId id) {
        return files.get(id);
    }

    public List<String> failureReasons() {
        return failures;
    }

    /** The next {@code count} claims find the database out of reach. */
    public void unreachableForClaims(int count) {
        unreachableClaims = count;
    }

    /** The next {@code count} outcome writes find the database out of reach — a drained pool. */
    public void unreachableForWrites(int count) {
        unreachableWrites = count;
    }

    /** Another worker took the file over: the old claim's token no longer matches. */
    public void stealLease(FileId id) {
        StoredFile current = files.get(id);
        Lease stolen = current.currentLease().orElseThrow();
        files.put(id, new StoredFile(current.id(), current.owner(), current.filename(), current.contentType(),
                current.sizeBytes(), current.sha256(), current.area(), current.status(), current.reason().orElse(null),
                current.scan().orElse(null), current.attempts(),
                new Lease(LeaseToken.random(), "thief", stolen.expiresAt()),
                current.uploadedAt(), current.statusChangedAt(), current.version()));
    }

    @Override
    public synchronized Optional<StoredFile> claimNextDue(String workerId, LeaseToken token, LeaseTerms lease,
                                                          int maxAttempts) {
        if (unreachableClaims > 0) {
            unreachableClaims--;
            throw new WorkQueueUnavailableException("simulated: no connection available", null);
        }
        for (StoredFile file : files.values()) {
            if (file.status().isClaimable() && file.attempts() < maxAttempts) {
                StoredFile claimed = file.claimedBy(token, workerId, Instant.now(), lease.forSize(file.sizeBytes()));
                files.put(file.id(), claimed);
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized boolean writeVerdict(StoredFile after, LeaseToken claim, Duration promotionLease) {
        failIfUnreachable();
        return replaceIf(after, claim, FileStatus.SCANNING);
    }

    @Override
    public synchronized boolean markAvailable(StoredFile after, LeaseToken claim) {
        return replaceIf(after, claim, FileStatus.PROMOTING);
    }

    @Override
    public synchronized boolean writeTechnicalFailure(StoredFile after, LeaseToken claim, Duration retryIn,
                                                      String error) {
        failIfUnreachable();
        boolean written = replaceIf(after, claim, FileStatus.SCANNING, FileStatus.PROMOTING);
        if (written) {
            failures.add(error);
        }
        return written;
    }

    @Override
    public synchronized boolean releaseOnShutdown(StoredFile after, LeaseToken claim) {
        return replaceIf(after, claim, FileStatus.SCANNING, FileStatus.PROMOTING);
    }

    @Override
    public int reclaimExpiredLeases(int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
        return 0;
    }

    private void failIfUnreachable() {
        if (unreachableWrites > 0) {
            unreachableWrites--;
            throw new WorkQueueUnavailableException("simulated: no connection available", null);
        }
    }

    private boolean replaceIf(StoredFile after, LeaseToken claim, FileStatus... expected) {
        StoredFile current = files.get(after.id());
        boolean statusMatches = List.of(expected).contains(current.status());
        boolean leaseMatches = current.currentLease().map(lease -> lease.token().equals(claim)).orElse(false);
        if (!statusMatches || !leaseMatches) {
            return false;
        }
        files.put(after.id(), after);
        return true;
    }
}
