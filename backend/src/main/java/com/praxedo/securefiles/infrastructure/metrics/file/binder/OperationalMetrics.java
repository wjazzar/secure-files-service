package com.praxedo.securefiles.infrastructure.metrics.file.binder;

import java.time.Duration;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.praxedo.securefiles.application.file.port.in.MonitorFilesUseCase;

/**
 * The gauges read from the database ({@code ARCHITECTURE.md} §12) — the
 * <strong>lag</strong> of the analysis queue, as a broker reports a consumer's:
 * how many files wait, how many bytes, and for how long the oldest has.
 *
 * <p>Computed when scraped, not cached: a gauge that lags is a gauge that lies
 * during exactly the incident it is meant to reveal. When the database cannot
 * answer, the gauge reports {@code NaN} — "unknown" — never a reassuring zero.
 */
@Component
class OperationalMetrics implements MeterBinder {

    private static final Logger LOG = LoggerFactory.getLogger(OperationalMetrics.class);

    private final MonitorFilesUseCase monitoringUseCase;

    OperationalMetrics(MonitorFilesUseCase monitoringUseCase) {
        this.monitoringUseCase = monitoringUseCase;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("praxedo.queue.depth", () -> safely("queue depth", () -> (double) monitoringUseCase.queueDepth()))
                .description("Files with analysis or promotion work ahead of them: the lag, in files")
                .register(registry);
        Gauge.builder("praxedo.queue.depth.bytes", () -> safely("queue bytes", () -> (double) monitoringUseCase.queueBytes()))
                .description("Total size of the files with work ahead of them: the lag, in bytes")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder("praxedo.queue.oldest_pending_age", () -> safely("oldest pending age",
                        () -> monitoringUseCase.oldestPendingAge().map(Duration::toMillis).orElse(0L) / 1000.0))
                .description("How long the oldest file with work ahead has been waiting")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("praxedo.invariant.violations", () -> safely("invariant violations",
                        () -> (double) monitoringUseCase.invariantViolations()))
                .description("Rows whose status and storage area disagree — must stay at zero")
                .register(registry);
    }

    private static double safely(String what, Supplier<Double> reading) {
        try {
            return reading.get();
        } catch (RuntimeException unavailable) {
            LOG.debug("Gauge '{}' unavailable: {}", what, unavailable.getMessage());
            return Double.NaN;
        }
    }
}
