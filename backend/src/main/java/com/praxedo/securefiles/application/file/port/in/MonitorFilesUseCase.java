package com.praxedo.securefiles.application.file.port.in;

import java.time.Duration;
import java.util.Optional;

/**
 * What operations need to know about the files, as numbers.
 *
 * <p>Called by the metrics adapter (Prometheus gauges, health indicator).
 */
public interface MonitorFilesUseCase {

    /** Files with analysis or promotion work ahead of them: the lag, in files. */
    long queueDepth();

    /**
     * Their total size: the lag, in bytes. Analysis time grows with size, so
     * this — divided by the throughput in bytes per second — is what tells how
     * long the backlog takes to clear; a count of files does not.
     */
    long queueBytes();

    /** How long the oldest file with work ahead has been waiting; empty when none is. */
    Optional<Duration> oldestPendingAge();

    /** Rows whose status and storage area disagree. Must always be zero. */
    long invariantViolations();
}
