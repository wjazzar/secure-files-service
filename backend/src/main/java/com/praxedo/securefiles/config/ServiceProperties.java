package com.praxedo.securefiles.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.praxedo.securefiles.application.file.model.UploadLimits;
import com.praxedo.securefiles.application.file.model.WorkerSettings;

/**
 * Every bound the service works within, in one typed place.
 *
 * <p>Nothing here is a constant in the code: each limit can be changed without
 * recompiling, which is what makes the load and failure tests of
 * {@code docs/31} possible at all (testability requirement TST-4).
 *
 * <p>The upload and worker bounds are bound straight onto the application's
 * own records, {@link UploadLimits} and {@link WorkerSettings}, which document
 * and check each of them: no second copy, field by field. The analysis loops
 * themselves — how many, how often they poll, how long they drain — are the
 * scheduling adapter's, read where they are used.
 */
@ConfigurationProperties("praxedo")
public record ServiceProperties(UploadLimits upload, WorkerSettings worker, Maintenance maintenance) {

    /**
     * The maintenance delays are tied to the slowest upload accepted. Shorter,
     * the sweep would take the object of an upload still arriving for an
     * orphan, and the purge would drop its idempotency key — the upload then
     * fails at the very end, and a retry with the same key stores the file
     * twice. Checked at start-up: a wrong pair of settings does not boot.
     */
    public ServiceProperties {
        Objects.requireNonNull(upload, "praxedo.upload");
        Objects.requireNonNull(worker, "praxedo.worker");
        Objects.requireNonNull(maintenance, "praxedo.maintenance");
        Duration slowestUpload = upload.transferDeadline().forSize(upload.maxSizeBytes());
        if (maintenance.orphanAge().compareTo(slowestUpload) <= 0
                || maintenance.abandonedUploadAfter().compareTo(slowestUpload) <= 0) {
            throw new IllegalArgumentException("praxedo.maintenance.orphan-age and abandoned-upload-after must exceed "
                    + "the transfer deadline of the largest upload (" + slowestUpload.toMinutes() + " min)");
        }
    }

    /**
     * @param orphanAge            how old a quarantined object must be before the
     *                             sweep may consider it an orphan — longer than
     *                             the slowest upload accepted
     * @param sweepBatch           how many objects one sweep looks at
     * @param abandonedUploadAfter an idempotency reservation still in progress
     *                             after this long belongs to a node that died —
     *                             longer than the slowest upload accepted
     */
    public record Maintenance(Duration orphanAge, int sweepBatch, Duration abandonedUploadAfter) {
    }
}
