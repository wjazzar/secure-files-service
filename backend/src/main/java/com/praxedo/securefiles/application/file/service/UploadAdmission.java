package com.praxedo.securefiles.application.file.service;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import com.praxedo.securefiles.application.file.exception.UploadRefusedException;
import com.praxedo.securefiles.application.file.model.UploadLimits;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;

/**
 * Decides whether one more upload may start on this node — before a single
 * byte of its body is read, and without the database in the common case.
 *
 * <p>Two bounds:
 * <ul>
 *   <li><strong>uploads in progress on this node</strong>, held by a
 *       {@link Semaphore} taken without waiting. With virtual threads nothing
 *       else bounds them: in the capacity campaigns, hundreds of simultaneous
 *       requests drained the database pool, the workers starved, and useful
 *       throughput fell by half past saturation;</li>
 *   <li><strong>pending files</strong> — not yet terminal, across the cluster —
 *       read from a {@link PendingFileCountCache}. A refusal must cost less
 *       than an acceptance: counting the queue for every request made each
 *       {@code 429} compete with the workers for a connection.</li>
 * </ul>
 *
 * <p>"Pending" here is broader than the public status {@code PENDING}: files
 * being analysed or promoted count too, because they occupy the quarantine
 * just the same.
 *
 * <p>The pending bound is approximate — it always was: counting then inserting
 * was never atomic across concurrent uploads and nodes. It may now be
 * overshot by what arrives within one refresh interval.
 */
final class UploadAdmission {

    private final UploadLimits limits;
    private final Semaphore concurrentUploadPermits;
    private final PendingFileCountCache pendingFiles;

    UploadAdmission(FileCatalog catalog, UploadLimits limits, Clock clock) {
        Objects.requireNonNull(catalog, "catalog");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.concurrentUploadPermits = new Semaphore(limits.maxConcurrentUploads());
        this.pendingFiles = new PendingFileCountCache(catalog::countPendingFiles, limits.pendingCountMaxAge(), clock);
    }

    /**
     * @return the permit this upload holds on the node, to close when it is
     *         over — accepted, refused or failed
     * @throws UploadRefusedException.TooManyConcurrentUploads this node is full
     * @throws UploadRefusedException.TooManyPending          the queue is full
     */
    UploadPermit admit() {
        if (!concurrentUploadPermits.tryAcquire()) {
            throw new UploadRefusedException.TooManyConcurrentUploads();
        }
        try {
            if (pendingFiles.current() >= limits.maxPendingFiles()) {
                throw new UploadRefusedException.TooManyPending();
            }
            return new UploadPermit();
        } catch (RuntimeException refused) {
            concurrentUploadPermits.release();
            throw refused;
        }
    }

    /** A place taken on this node; closing it twice frees it once. */
    final class UploadPermit implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                concurrentUploadPermits.release();
            }
        }
    }
}
