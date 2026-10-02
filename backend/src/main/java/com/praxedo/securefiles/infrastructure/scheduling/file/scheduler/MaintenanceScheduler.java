package com.praxedo.securefiles.infrastructure.scheduling.file.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.praxedo.securefiles.application.file.port.in.MaintainFilesUseCase;

/**
 * The clock that triggers the self-healing chores: reclaiming abandoned work,
 * collecting quarantine debris, forgetting expired idempotency keys.
 *
 * <p>It is a <em>driving</em> adapter: it decides <strong>when</strong>, the use
 * case decides <strong>what</strong>. It knows nothing of the queue, the
 * storage or the idempotency store — only {@link MaintainFilesUseCase}.
 *
 * <p><strong>No distributed lock.</strong> Every chore is safe to run on every
 * node at once — the reaper and the purge are conditional statements, deleting
 * an object twice is a no-op. That is why ShedLock or an equivalent was not
 * added.
 *
 * <p>A chore that fails logs and waits for its next turn; it never takes the
 * scheduler down with it.
 */
@Component
@ConditionalOnProperty(name = "praxedo.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class MaintenanceScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final MaintainFilesUseCase maintenanceUseCase;

    MaintenanceScheduler(MaintainFilesUseCase maintenanceUseCase) {
        this.maintenanceUseCase = maintenanceUseCase;
    }

    @Scheduled(initialDelayString = "${praxedo.scheduling.reaper-interval}",
            fixedDelayString = "${praxedo.scheduling.reaper-interval}")
    void reclaimAbandonedWork() {
        try {
            int reclaimed = maintenanceUseCase.reclaimExpiredLeases();
            if (reclaimed > 0) {
                LOG.info("Reclaimed {} file(s) whose lease had expired", reclaimed);
            }
        } catch (RuntimeException failure) {
            LOG.warn("Reclaiming expired leases failed; it will run again at its next turn", failure);
        }
    }

    @Scheduled(initialDelayString = "${praxedo.scheduling.sweep-interval}",
            fixedDelayString = "${praxedo.scheduling.sweep-interval}")
    void sweepQuarantine() {
        try {
            int removed = maintenanceUseCase.sweepQuarantine();
            if (removed > 0) {
                LOG.info("Quarantine sweep removed {} object(s) nobody references any more", removed);
            }
        } catch (RuntimeException failure) {
            LOG.warn("Quarantine sweep failed; it will run again at its next turn", failure);
        }
    }

    @Scheduled(initialDelayString = "${praxedo.scheduling.sweep-interval}",
            fixedDelayString = "${praxedo.scheduling.sweep-interval}")
    void purgeIdempotencyKeys() {
        try {
            maintenanceUseCase.purgeIdempotencyKeys();
        } catch (RuntimeException failure) {
            LOG.warn("Idempotency purge failed; it will run again at its next turn", failure);
        }
    }
}
