package com.praxedo.securefiles.application.file.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

/**
 * Removes from the quarantine what nobody needs any more.
 *
 * <p>Two kinds of object qualify:
 * <ul>
 *   <li><strong>orphans</strong> — written by an upload whose commit never
 *       happened (crash, database outage). The upload wrote the object first
 *       precisely so that a partial failure leaves this invisible debris rather
 *       than a broken reference; this is where the debris is collected;</li>
 *   <li><strong>sources of promoted files</strong> — the promotion deletes them
 *       itself, but a crash between the commit and that deletion leaves one
 *       behind.</li>
 * </ul>
 *
 * <p>Everything else stays: files still waiting or being analysed obviously,
 * and blocked files too — an infected object is kept, never served, as evidence
 * (decision D-07).
 *
 * <p>Only objects older than {@code orphanAge} are considered: an upload in
 * flight has written its object but not yet committed its row, and must not be
 * mistaken for an orphan. The age exceeds the slowest upload the service
 * accepts, which is checked at start-up.
 *
 * <p>Each sweep resumes where the previous one stopped. The objects kept as
 * evidence come back in every listing: started from the first key each time,
 * a batch filled with them would hide every orphan behind them for ever.
 * The position is kept in memory only: a node that restarts begins again from
 * the first key, which costs a pass, never an orphan.
 */
public class QuarantineSweeper {

    private final WorkerStorage storage;
    private final FileCatalog catalog;
    private final Clock clock;
    private final Duration orphanAge;
    private final int batchSize;
    private final AtomicReference<ObjectKey> resumeAfter = new AtomicReference<>();

    public QuarantineSweeper(WorkerStorage storage, FileCatalog catalog, Clock clock,
                             Duration orphanAge, int batchSize) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orphanAge = Objects.requireNonNull(orphanAge, "orphan age");
        this.batchSize = batchSize;
    }

    /** @return how many objects were removed */
    public int sweep() {
        List<ObjectKey> candidates = storage.listQuarantinedBefore(
                clock.instant().minus(orphanAge), resumeAfter.get(), batchSize);
        // A full batch may have more behind it: the next sweep resumes there. A short one reached the end.
        resumeAfter.set(candidates.size() < batchSize ? null : candidates.getLast());
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<String, FileStatus> statuses = catalog.statusesByObjectKey(
                candidates.stream().map(ObjectKey::value).toList());

        int removed = 0;
        for (ObjectKey key : candidates) {
            FileStatus status = statuses.get(key.value());
            if (status == null || status == FileStatus.AVAILABLE) {
                storage.deleteQuarantined(key);
                removed++;
            }
        }
        return removed;
    }
}
