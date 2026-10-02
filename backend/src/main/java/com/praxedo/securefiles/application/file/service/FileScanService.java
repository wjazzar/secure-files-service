package com.praxedo.securefiles.application.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.praxedo.securefiles.application.common.io.DeadlineInputStream;
import com.praxedo.securefiles.application.common.io.InspectingInputStream;
import com.praxedo.securefiles.application.file.exception.ObjectMissingException;
import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.application.file.exception.StorageUnavailableException;
import com.praxedo.securefiles.application.file.exception.WorkQueueUnavailableException;
import com.praxedo.securefiles.application.file.model.WorkerSettings;
import com.praxedo.securefiles.application.file.port.in.ScanFilesUseCase;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner.ScanOutcome;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;

/**
 * Takes the next file, has it analysed, and records what the antivirus
 * concluded — about <em>these exact bytes</em>.
 *
 * <p>Four properties hold whatever happens:
 * <ul>
 *   <li><strong>The verdict is bound to what was scanned.</strong> The bytes are
 *       hashed on their way to the engine; a verdict about bytes whose digest
 *       differs from the upload's is not recorded, however clean it is. A storage
 *       that altered an object would be caught here, not served.</li>
 *   <li><strong>A failure is never a verdict.</strong> An engine that is down,
 *       slow or unreadable sends the file back to the queue with a delay; it is
 *       given up on only after its last attempt, and then as {@code FAILED}.</li>
 *   <li><strong>A worker that lost its lease writes nothing.</strong> Every write
 *       is conditioned on the token of this claim — and the worker stops
 *       reading when its lease ends, instead of holding an analysis slot for
 *       a verdict that would be refused.</li>
 *   <li><strong>A database out of reach loses no file.</strong> An outcome
 *       already paid for — an analysis done, a failure established — is written
 *       again a few times; if the database stays out of reach, the file's lease,
 *       sized to the file, returns it to the queue.</li>
 * </ul>
 *
 * <p>One call analyses one file, start to finish. How many analyses run at once
 * is therefore decided by how many callers there are — the analysis loops of
 * the scheduling adapter, a fixed number per node — and not here. Uploads do
 * not start analyses: a burst of them lengthens the queue, nothing more.
 */
public class FileScanService implements ScanFilesUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(FileScanService.class);

    /**
     * Writing an outcome is retried this many times, the delay doubling from
     * {@link #WRITE_RETRY_DELAY}. Each attempt may itself wait for a pooled
     * connection: the whole stays well inside the shortest lease.
     */
    private static final int WRITE_ATTEMPTS = 3;
    private static final Duration WRITE_RETRY_DELAY = Duration.ofMillis(250);

    private final FileWorkQueue queue;
    private final WorkerStorage storage;
    private final AntivirusScanner scanner;
    private final FilePromotionService promotion;
    private final WorkerSettings settings;
    private final Clock clock;
    private final String workerId;

    /** The claims currently being worked on, so a clean shutdown can hand them back. */
    private final Map<LeaseToken, StoredFile> inFlight = new ConcurrentHashMap<>();

    public FileScanService(FileWorkQueue queue, WorkerStorage storage, AntivirusScanner scanner,
                      FilePromotionService promotion, WorkerSettings settings, Clock clock, String workerId) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.promotion = Objects.requireNonNull(promotion, "promotion");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerId = Objects.requireNonNull(workerId, "worker id");
    }

    /**
     * One unit of work.
     *
     * @return {@code true} when a file was handled — the caller may loop at once;
     *         {@code false} when there was nothing to do or the engine is down
     */
    @Override
    public boolean processNext() {
        // The health gate comes BEFORE the claim: an outage must not burn attempts.
        if (!scanner.isAvailable()) {
            return false;
        }
        LeaseToken claim = LeaseToken.random();
        Optional<StoredFile> claimed;
        try {
            claimed = queue.claimNextDue(workerId, claim, settings.lease(), settings.maxAttempts());
        } catch (WorkQueueUnavailableException unreachable) {
            // Nothing is lost: at worst the claim was committed and its answer
            // was not, and the lease returns that file. Wait like an empty queue.
            LOG.warn("Work queue out of reach, no file taken: {}", unreachable.getMessage());
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }
        inFlight.put(claim, claimed.get());
        // Every line logged while handling this file carries its identifier.
        try (MDC.MDCCloseable tagged = MDC.putCloseable("fileId", claimed.get().id().toString())) {
            handle(claimed.get(), claim);
        } finally {
            inFlight.remove(claim);
        }
        return true;
    }

    private void handle(StoredFile file, LeaseToken claim) {
        ScanVerdict verdict;
        try {
            verdict = analyse(file);
        } catch (ScannerUnavailableException | StorageUnavailableException | ObjectMissingException
                 | UncheckedIOException | LeaseOutlasted failure) {
            recordFailure(file, claim, failure.getMessage());
            return;
        }

        if (!verdict.scannedContent().equals(file.sha256())) {
            // Never recorded, whatever the engine said: it is a verdict about other bytes.
            LOG.error("Analysis of {} refused: the stored bytes do not match the uploaded ones", file.id());
            recordFailure(file, claim, "stored content does not match its upload digest");
            return;
        }

        StoredFile concluded = file.scanned(verdict, clock.instant());
        Duration promotionLease = settings.promotionLease().forSize(file.sizeBytes());
        if (!written(() -> queue.writeVerdict(concluded, claim, promotionLease), file)) {
            return;
        }
        LOG.info("File {} analysed: {}", file.id(), verdict.result());

        if (concluded.status() == FileStatus.PROMOTING) {
            inFlight.put(claim, concluded);
            try {
                promotion.promote(concluded, claim, promotionLease);
            } catch (WorkQueueUnavailableException unreachable) {
                LOG.warn("Promotion of {} interrupted ({}): the file returns to the queue when its lease expires",
                        file.id(), unreachable.getMessage());
            }
        }
    }

    /**
     * Streams the quarantined object to the engine, hashing it on the way —
     * within the claim's lease.
     *
     * <p>Past the lease, the verdict would be refused anyway: its write is
     * conditioned on this claim's token, and the file may already be someone
     * else's. So the transfer stops there, whether the storage trickles or the
     * engine reads slowly, and the slot goes back to a file that can still be
     * concluded.
     *
     * <p>The deadline is the one the database granted, never recomputed here:
     * the claimed row carries the claim's instant and the lease's end, both on
     * the database's clock, and only their difference — a duration — crosses
     * over to this node's clock. The two clocks are never compared, so a node
     * whose clock is off does not cut every analysis short. At worst the local
     * deadline ends a few milliseconds after the database's, which the token
     * still fences.
     */
    private ScanVerdict analyse(StoredFile file) {
        Duration granted = Duration.between(file.statusChangedAt(), file.currentLease().orElseThrow().expiresAt());
        Instant leaseEnds = clock.instant().plus(granted);
        try (InputStream stored = storage.openQuarantined(file.objectKey())) {
            DeadlineInputStream bounded = new DeadlineInputStream(stored, leaseEnds, clock);
            InspectingInputStream measured = new InspectingInputStream(bounded, file.sizeBytes());
            ScanOutcome outcome;
            try {
                outcome = scanner.scan(measured, file.sizeBytes());
            } catch (RuntimeException failure) {
                throw bounded.expired() ? new LeaseOutlasted(failure) : failure;
            }
            if (measured.count() != file.sizeBytes()) {
                throw new ScannerUnavailableException("The engine stopped reading before the end of the file");
            }
            return new ScanVerdict(outcome.result(), outcome.detail(), outcome.engine(), outcome.engineVersion(),
                    outcome.signatureVersion(), measured.digest(), clock.instant(), outcome.duration());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void recordFailure(StoredFile file, LeaseToken claim, String reason) {
        StoredFile failed = file.technicalFailure(settings.maxAttempts(), clock.instant());
        Duration retryIn = settings.retryDelay(file.attempts());
        if (written(() -> queue.writeTechnicalFailure(failed, claim, retryIn, reason), file)) {
            LOG.warn("Analysis of {} failed ({}); {}", file.id(), reason,
                    failed.status() == FileStatus.FAILED_FINAL ? "no attempt left" : "will retry");
        }
    }

    /**
     * Records an outcome that is already paid for through a database that may
     * be briefly out of reach — a pool drained by a burst of uploads, a lost
     * connection. The write is guarded by this claim's token, so repeating it is
     * harmless. If the database stays out of reach, the lease takes over.
     *
     * @return whether the outcome was written; when it was not — the lease was
     *         lost, or the database stayed out of reach — the reason is logged here
     */
    private boolean written(BooleanSupplier write, StoredFile file) {
        Duration delay = WRITE_RETRY_DELAY;
        for (int attempt = 1; ; attempt++) {
            try {
                if (!write.getAsBoolean()) {
                    LOG.warn("Outcome of {} discarded: the lease was lost, the file has a new owner", file.id());
                    return false;
                }
                return true;
            } catch (WorkQueueUnavailableException unreachable) {
                if (attempt == WRITE_ATTEMPTS || !pause(delay)) {
                    LOG.warn("Outcome of {} not recorded ({}): the file returns to the queue when its lease expires",
                            file.id(), unreachable.getMessage());
                    return false;
                }
                delay = delay.multipliedBy(2);
            }
        }
    }

    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Hands back every claim still in progress — the node is stopping.
     *
     * <p>The attempt is given back too: nothing was concluded, so nothing should
     * be counted. Each release is conditioned on its token, so a claim that
     * finished in the meantime is simply not touched.
     *
     * @return how many files went back to the queue
     */
    @Override
    public int releaseInFlight() {
        int released = 0;
        for (Map.Entry<LeaseToken, StoredFile> claim : inFlight.entrySet()) {
            StoredFile file = claim.getValue();
            if (file.status().holdsLease()
                    && queue.releaseOnShutdown(file.releasedOnShutdown(clock.instant()), claim.getKey())) {
                released++;
            }
        }
        return released;
    }

    /** The transfer to the engine outlasted the claim's lease: a technical failure, not a verdict. */
    static final class LeaseOutlasted extends RuntimeException {
        LeaseOutlasted(Throwable cause) {
            super("The analysis outlasted its lease", cause);
        }
    }
}
