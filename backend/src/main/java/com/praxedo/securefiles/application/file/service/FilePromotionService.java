package com.praxedo.securefiles.application.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.praxedo.securefiles.application.common.io.DeadlineInputStream;
import com.praxedo.securefiles.application.common.io.InspectingInputStream;
import com.praxedo.securefiles.application.file.exception.ObjectMissingException;
import com.praxedo.securefiles.application.file.exception.StorageUnavailableException;
import com.praxedo.securefiles.application.file.model.WorkerSettings;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;

/**
 * Makes a cleared file servable: {@code PROMOTING -> AVAILABLE}.
 *
 * <p><strong>The content is re-read, not copied server-side.</strong> A storage
 * copy would be faster, but it does not return the digest of what it copied —
 * so the file would become servable without anyone checking that the bytes in
 * the servable area are the bytes that were analysed. Here they are hashed on
 * their way across, and compared with the attestation before the file is
 * declared available.
 *
 * <p><strong>The commit point is the database, not the object.</strong> The copy
 * is written straight to its final key: an object in the servable area is never
 * served unless its row says {@code AVAILABLE}, and that happens only through
 * the compare-and-set below, after the check. Every interruption is therefore
 * recoverable:
 * <ul>
 *   <li>before or during the copy — the lease expires, the file is analysed and
 *       promoted again, overwriting the same key with the same bytes;</li>
 *   <li>after the copy, before the compare-and-set — same;</li>
 *   <li>after the compare-and-set, before the source is deleted — the file is
 *       servable, and the quarantine sweep removes the leftover source.</li>
 * </ul>
 *
 * <p><strong>The copy stops when the promotion lease ends.</strong> Past it,
 * the compare-and-set would be refused, so a copy that trickles on is work for
 * nobody; it fails instead, and the file goes back to the queue at once rather
 * than when the reaper notices.
 */
public class FilePromotionService {

    private static final Logger LOG = LoggerFactory.getLogger(FilePromotionService.class);

    private final FileWorkQueue queue;
    private final WorkerStorage storage;
    private final WorkerSettings settings;
    private final Clock clock;

    public FilePromotionService(FileWorkQueue queue, WorkerStorage storage, WorkerSettings settings, Clock clock) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param lease the promotion lease the database was just asked for, with the
     *              verdict — measured here on this node's clock, never recomputed
     * @return the file as it ended up: {@code AVAILABLE}, or back in the queue
     */
    public StoredFile promote(StoredFile promoting, LeaseToken claim, Duration lease) {
        if (promoting.status() != FileStatus.PROMOTING) {
            throw new IllegalArgumentException("Only a file with a clean verdict can be promoted");
        }

        Instant leaseEnds = clock.instant().plus(lease);
        DeadlineInputStream bounded = null;
        InspectingInputStream copied;
        try (InputStream source = storage.openQuarantined(promoting.objectKey())) {
            bounded = new DeadlineInputStream(source, leaseEnds, clock);
            copied = new InspectingInputStream(bounded, promoting.sizeBytes());
            storage.writeServable(promoting.objectKey(), copied, promoting.sizeBytes());
        } catch (ObjectMissingException | StorageUnavailableException | IOException | UncheckedIOException failure) {
            return failed(promoting, claim, bounded != null && bounded.expired()
                    ? "promotion copy outlasted its lease"
                    : "promotion copy failed: " + failure.getMessage());
        }

        // The last check before a file becomes servable: these must be the analysed bytes.
        if (copied.count() != promoting.sizeBytes() || !copied.digest().equals(promoting.sha256())) {
            LOG.error("Promotion of {} refused: the copied bytes do not match the attested content", promoting.id());
            deleteQuietly(promoting);
            return failed(promoting, claim, "promoted copy does not match the attested content");
        }

        StoredFile available = promoting.promoted(clock.instant());
        if (!queue.markAvailable(available, claim)) {
            // The lease was lost while copying. Someone else owns the file now;
            // the object written is the same content under the same key.
            LOG.warn("Lease lost while promoting {}; leaving it to the new owner", promoting.id());
            return promoting;
        }

        try {
            storage.deleteQuarantined(promoting.objectKey());
        } catch (RuntimeException leftBehind) {
            // Harmless: the quarantine sweep removes sources of available files.
            LOG.info("Source of {} left in quarantine; the sweep will remove it", promoting.id());
        }
        return available;
    }

    private StoredFile failed(StoredFile promoting, LeaseToken claim, String reason) {
        StoredFile failed = promoting.technicalFailure(settings.maxAttempts(), clock.instant());
        queue.writeTechnicalFailure(failed, claim, settings.retryDelay(promoting.attempts()), reason);
        return failed;
    }

    private void deleteQuietly(StoredFile file) {
        try {
            storage.deleteServable(file.objectKey());
        } catch (RuntimeException ignored) {
            // Not servable anyway: the row is not AVAILABLE.
        }
    }
}
