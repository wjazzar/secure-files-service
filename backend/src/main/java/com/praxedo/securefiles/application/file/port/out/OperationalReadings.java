package com.praxedo.securefiles.application.file.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * The aggregates operations read from the store of record — kept apart from
 * {@link FileCatalog}, which serves the API.
 */
public interface OperationalReadings {

    /** Measured on the store's clock, like every lease. */
    Optional<Duration> oldestPendingAge();

    /** The total size of the files with work ahead of them: the lag, in bytes. */
    long pendingBytes();

    /** ARCHITECTURE.md §6.4: rows whose status and storage area disagree. */
    long invariantViolations();
}
