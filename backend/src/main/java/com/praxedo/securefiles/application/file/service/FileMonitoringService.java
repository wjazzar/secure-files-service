package com.praxedo.securefiles.application.file.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.praxedo.securefiles.application.file.port.in.MonitorFilesUseCase;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.OperationalReadings;

/**
 * The numbers operations watch. Before this service, the metrics adapter read
 * the persistence adapter directly; it now asks the core, like every caller.
 */
public class FileMonitoringService implements MonitorFilesUseCase {

    private final FileCatalog catalog;
    private final OperationalReadings readings;

    public FileMonitoringService(FileCatalog catalog, OperationalReadings readings) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.readings = Objects.requireNonNull(readings, "readings");
    }

    @Override
    public long queueDepth() {
        return catalog.countPendingFiles();
    }

    @Override
    public long queueBytes() {
        return readings.pendingBytes();
    }

    @Override
    public Optional<Duration> oldestPendingAge() {
        return readings.oldestPendingAge();
    }

    @Override
    public long invariantViolations() {
        return readings.invariantViolations();
    }
}
