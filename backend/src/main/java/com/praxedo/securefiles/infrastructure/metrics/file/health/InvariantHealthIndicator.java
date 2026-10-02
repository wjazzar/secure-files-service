package com.praxedo.securefiles.infrastructure.metrics.file.health;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import com.praxedo.securefiles.application.file.port.in.MonitorFilesUseCase;

/**
 * {@code /actuator/health} turns {@code DOWN} if the invariant query ever
 * returns something other than zero.
 *
 * <p>Deliberately <em>not</em> part of the readiness group: every node reads the
 * same database, so a readiness failure would take the whole service out of
 * rotation — including the download of files that are perfectly fine. The
 * alarm must ring; the decision to stop serving belongs to a human.
 */
@Component("invariant")
class InvariantHealthIndicator extends AbstractHealthIndicator {

    private final MonitorFilesUseCase monitoringUseCase;

    InvariantHealthIndicator(MonitorFilesUseCase monitoringUseCase) {
        super("The invariant query could not be run");
        this.monitoringUseCase = monitoringUseCase;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long violations = monitoringUseCase.invariantViolations();
        if (violations == 0) {
            builder.up();
        } else {
            builder.down().withDetail("violations", violations);
        }
    }
}
