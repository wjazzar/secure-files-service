package com.praxedo.securefiles.application.file.service;

import java.time.Duration;
import java.util.Objects;

import com.praxedo.securefiles.application.file.model.WorkerSettings;
import com.praxedo.securefiles.application.file.port.in.MaintainFilesUseCase;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.idempotency.port.out.IdempotencyStore;

/**
 * The self-healing chores, in one place.
 *
 * <p>Before this service, the scheduler reached the work queue and the
 * idempotency store directly — an adapter driving other adapters, past the
 * core. It now goes through this use case like every other caller.
 */
public class FileMaintenanceService implements MaintainFilesUseCase {

    private final FileWorkQueue queue;
    private final QuarantineSweeper sweeper;
    private final IdempotencyStore idempotency;
    private final WorkerSettings settings;
    private final Duration abandonedUploadAfter;

    public FileMaintenanceService(FileWorkQueue queue, QuarantineSweeper sweeper, IdempotencyStore idempotency,
                                  WorkerSettings settings, Duration abandonedUploadAfter) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.abandonedUploadAfter = Objects.requireNonNull(abandonedUploadAfter, "abandoned upload delay");
    }

    /**
     * Work whose lease expired — a worker that crashed, froze, or lost its
     * network — goes back to the queue with a growing delay, or is given up on
     * once its attempts are spent. This is what makes a killed worker a
     * non-event: nobody has to intervene.
     */
    @Override
    public int reclaimExpiredLeases() {
        return queue.reclaimExpiredLeases(settings.maxAttempts(), settings.backoffBase(), settings.backoffMax());
    }

    @Override
    public int sweepQuarantine() {
        return sweeper.sweep();
    }

    /** Keys past their time to live, and reservations abandoned by a node that died mid-upload. */
    @Override
    public int purgeIdempotencyKeys() {
        return idempotency.purge(abandonedUploadAfter);
    }
}
