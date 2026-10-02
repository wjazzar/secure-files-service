package com.praxedo.securefiles.infrastructure.persistence.file.adapter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.praxedo.securefiles.application.file.exception.WorkQueueUnavailableException;
import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.Lease;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StatusReason;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.infrastructure.persistence.common.config.DataSourceConfiguration;
import com.praxedo.securefiles.infrastructure.persistence.file.mapper.StoredFileRowMapper;
import com.praxedo.securefiles.infrastructure.persistence.file.mapper.FileStatusSql;

/**
 * The statements JPA cannot express, written by hand — and nothing else.
 *
 * <p>Three of them explain the whole split:
 * <ul>
 *   <li><strong>the claim</strong> is an {@code UPDATE … FOR UPDATE SKIP LOCKED
 *       … RETURNING}: it modifies <em>and</em> returns the row. Spring Data
 *       requires {@code @Modifying} for a statement that writes, and a
 *       {@code @Modifying} method cannot return an entity — so this one has no
 *       Spring Data form, native query or not;</li>
 *   <li><strong>the verdict write</strong> must answer "did I win" with a
 *       boolean, guarded by the token of this claim. {@code @Version} would
 *       throw instead, and would check the wrong thing;</li>
 *   <li><strong>the reaper</strong> is a bulk conditional update whose backoff
 *       is computed in SQL, therefore persisted.</li>
 * </ul>
 *
 * <p>Everything else — reading by id, by owner, paginated, searched, counted —
 * lives in {@link JpaFileCatalog}, where Spring Data is shorter and clearer.
 *
 * <p>⚠️ These statements bypass the persistence context on purpose. They must
 * not run in a transaction that also loaded the same file through JPA (see
 * {@link JpaFileCatalog}).
 *
 * <p><strong>All timestamps come from {@code clock_timestamp()}</strong>, the
 * database's clock, and never from a node's. With several workers, a skewed
 * clock would let one of them declare a lease expired while the work is still
 * running.
 */
@Repository
public class JdbcFileWorkQueue implements FileWorkQueue {

    /** SLO-6 of docs/31: a file is available, or blocked, within two minutes (p95). */
    private static final Duration SLO_TIME_TO_VERDICT = Duration.ofMinutes(2);

    /** The lag, per file, on the database clock — read back by the statement that ends the work. */
    private static final String LAG_SECONDS = "extract(epoch FROM clock_timestamp() - uploaded_at)";

    private final JdbcClient jdbc;
    private final MeterRegistry registry;
    private final Counter rejectedVerdicts;
    private final Counter technicalFailures;

    /** @param queuePool the queue's own pool: requests to the API cannot starve the workers */
    public JdbcFileWorkQueue(@Qualifier(DataSourceConfiguration.QUEUE) DataSource queuePool, MeterRegistry registry) {
        this.jdbc = JdbcClient.create(queuePool);
        this.registry = registry;
        this.rejectedVerdicts = Counter.builder("praxedo.scan.verdict.rejected")
                .description("Verdicts refused because their lease was lost (zombie worker) — should stay rare")
                .register(registry);
        this.technicalFailures = Counter.builder("praxedo.work.technical_failures")
                .description("Analyses or promotions that failed for a technical reason and were rescheduled")
                .register(registry);
        // Every final outcome registered at zero: a dashboard shows "none yet", not "no data".
        for (FileStatus status : FileStatus.values()) {
            if (status.isTerminal()) {
                lagOf(status.publicStatus());
                bytesOf(status.publicStatus());
            }
        }
    }

    /**
     * One statement does four things: it picks the most urgent due file, it
     * excludes the other workers ({@code SKIP LOCKED} — each takes a
     * <em>different</em> row instead of queueing behind the same one), it takes
     * the lease, and it counts the attempt.
     *
     * <p>{@code attempts < :maxAttempts} is not an optimisation: without it,
     * work whose attempts are exhausted would be picked up forever.
     */
    @Override
    public Optional<StoredFile> claimNextDue(String workerId, LeaseToken token, LeaseTerms lease, int maxAttempts) {
        return reachable("claim", () -> jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM stored_file
                     WHERE status IN (%s)
                       AND next_attempt_at <= clock_timestamp()
                       AND attempts < :maxAttempts
                     ORDER BY next_attempt_at, uploaded_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                )
                UPDATE stored_file f
                   SET status            = 'SCANNING',
                       lease_token       = :token,
                       lease_holder      = :worker,
                       -- In proportion to the size, known only once the row is claimed.
                       lease_expires_at  = clock_timestamp() + make_interval(
                           secs => :leaseMinSeconds + f.size_bytes / 1048576.0 * :leasePerMibSeconds),
                       attempts          = f.attempts + 1,
                       status_reason     = NULL,
                       status_changed_at = clock_timestamp(),
                       updated_at        = clock_timestamp(),
                       version           = f.version + 1
                  FROM candidate
                 WHERE f.id = candidate.id
                RETURNING f.*
                """.formatted(FileStatusSql.CLAIMABLE))
                .param("maxAttempts", maxAttempts)
                .param("token", token.value())
                .param("worker", workerId)
                .param("leaseMinSeconds", seconds(lease.minimum()))
                .param("leasePerMibSeconds", seconds(lease.perMebibyte()))
                .query(StoredFileRowMapper.INSTANCE)
                .optional());
    }

    /**
     * The guard is the token of <em>this</em> claim, plus a lease that has not
     * expired. A worker frozen long enough for the reaper to hand its work to
     * someone else writes nothing when it wakes up: zero rows, and the caller
     * learns it lost.
     */
    @Override
    public boolean writeVerdict(StoredFile after, LeaseToken claim, Duration promotionLease) {
        ScanVerdict verdict = after.scan().orElseThrow(
                () -> new IllegalArgumentException("A verdict transition must carry its verdict"));
        LeaseToken keptLease = after.currentLease().map(Lease::token).orElse(null);

        Optional<Double> lag = reachable("verdict", () -> jdbc.sql("""
                UPDATE stored_file
                   SET status                 = CAST(:status AS file_status),
                       status_reason          = :statusReason,
                       scan_result            = CAST(:scanResult AS scan_result),
                       scan_threat_name       = :threatName,
                       scan_engine            = :engine,
                       scan_engine_version    = :engineVersion,
                       scan_signature_version = :signatureVersion,
                       scanned_sha256         = :scannedSha256,
                       scanned_at             = :scannedAt,
                       scan_duration_ms       = :durationMs,
                       last_error             = NULL,
                       -- The promotion keeps the same claim, with a fresh deadline:
                       -- copying 500 MB takes longer than scanning them.
                       lease_token            = CAST(:keptLease AS uuid),
                       lease_holder           = CASE WHEN CAST(:keptLease AS uuid) IS NULL THEN NULL ELSE lease_holder END,
                       lease_expires_at       = CASE WHEN CAST(:keptLease AS uuid) IS NULL THEN NULL
                                                     ELSE clock_timestamp() + make_interval(secs => :promotionSeconds) END,
                       status_changed_at      = clock_timestamp(),
                       updated_at             = clock_timestamp(),
                       version                = version + 1
                 WHERE id          = :id
                   AND status      = 'SCANNING'
                   AND lease_token = :claim
                   AND lease_expires_at > clock_timestamp()
                RETURNING %s
                """.formatted(LAG_SECONDS))
                .param("status", after.status().name())
                .param("statusReason", after.reason().map(StatusReason::name).orElse(null))
                .param("scanResult", verdict.result().name())
                .param("threatName", verdict.threatName())
                .param("engine", verdict.engine())
                .param("engineVersion", verdict.engineVersion())
                .param("signatureVersion", verdict.signatureVersion())
                .param("scannedSha256", verdict.scannedContent().value())
                .param("scannedAt", OffsetDateTime.ofInstant(verdict.scannedAt(), ZoneOffset.UTC))
                .param("durationMs", (int) verdict.duration().toMillis())
                .param("keptLease", keptLease == null ? null : keptLease.value())
                .param("promotionSeconds", seconds(promotionLease))
                .param("id", after.id().value())
                .param("claim", claim.value())
                .query(Double.class)
                .optional());

        if (lag.isEmpty()) {
            rejectedVerdicts.increment();
            return false;
        }
        // Infected or unscannable: the end of the road. Clean: promotion comes next.
        if (after.isTerminal()) {
            completed(after.publicStatus(), after.sizeBytes(), lag.get());
        }
        return true;
    }

    @Override
    public boolean markAvailable(StoredFile after, LeaseToken claim) {
        Optional<Double> lag = reachable("promotion", () -> jdbc.sql("""
                UPDATE stored_file
                   SET status            = 'AVAILABLE',
                       storage_area      = 'SERVABLE',
                       status_reason     = NULL,
                       lease_token       = NULL,
                       lease_holder      = NULL,
                       lease_expires_at  = NULL,
                       status_changed_at = clock_timestamp(),
                       updated_at        = clock_timestamp(),
                       version           = version + 1
                 WHERE id          = :id
                   AND status      = 'PROMOTING'
                   AND lease_token = :claim
                   AND lease_expires_at > clock_timestamp()
                RETURNING %s
                """.formatted(LAG_SECONDS))
                .param("id", after.id().value())
                .param("claim", claim.value())
                .query(Double.class)
                .optional());

        lag.ifPresent(seconds -> completed(PublicStatus.AVAILABLE, after.sizeBytes(), seconds));
        return lag.isPresent();
    }

    @Override
    public boolean writeTechnicalFailure(StoredFile after, LeaseToken claim, Duration retryIn, String error) {
        Optional<Double> lag = reachable("technical failure", () -> jdbc.sql("""
                UPDATE stored_file
                   SET status            = CAST(:status AS file_status),
                       status_reason     = :statusReason,
                       last_error        = :error,
                       next_attempt_at   = clock_timestamp() + make_interval(secs => :retrySeconds),
                       lease_token       = NULL,
                       lease_holder      = NULL,
                       lease_expires_at  = NULL,
                       status_changed_at = clock_timestamp(),
                       updated_at        = clock_timestamp(),
                       version           = version + 1
                 WHERE id          = :id
                   AND status IN (%s)
                   AND lease_token = :claim
                   AND lease_expires_at > clock_timestamp()
                RETURNING %s
                """.formatted(FileStatusSql.LEASED, LAG_SECONDS))
                .param("status", after.status().name())
                .param("statusReason", after.reason().map(StatusReason::name).orElse(null))
                .param("error", truncate(error))
                .param("retrySeconds", seconds(retryIn))
                .param("id", after.id().value())
                .param("claim", claim.value())
                .query(Double.class)
                .optional());

        if (lag.isEmpty()) {
            return false;
        }
        technicalFailures.increment();
        // Out of attempts: given up for good. Otherwise: rescheduled, still in the lag.
        if (after.isTerminal()) {
            completed(after.publicStatus(), after.sizeBytes(), lag.get());
        }
        return true;
    }

    @Override
    public boolean releaseOnShutdown(StoredFile after, LeaseToken claim) {
        int rows = jdbc.sql("""
                UPDATE stored_file
                   SET status            = 'AWAITING_SCAN',
                       status_reason     = NULL,
                       attempts          = :attempts,
                       next_attempt_at   = clock_timestamp(),
                       lease_token       = NULL,
                       lease_holder      = NULL,
                       lease_expires_at  = NULL,
                       status_changed_at = clock_timestamp(),
                       updated_at        = clock_timestamp(),
                       version           = version + 1
                 WHERE id          = :id
                   AND status IN (%s)
                   AND lease_token = :claim
                """.formatted(FileStatusSql.LEASED))
                .param("attempts", after.attempts())
                .param("id", after.id().value())
                .param("claim", claim.value())
                .update();

        return rows == 1;
    }

    /**
     * Exponential backoff with jitter, computed <em>in SQL</em> — therefore
     * persisted. It survives a restart, which an in-memory retry policy does
     * not. The jitter keeps a batch of reclaimed work from starting again all
     * at once.
     */
    @Override
    public int reclaimExpiredLeases(int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
        List<Reclaimed> reclaimed = jdbc.sql("""
                UPDATE stored_file
                   SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED_FINAL'::file_status
                                     ELSE 'RETRY_WAIT'::file_status END,
                       status_reason = CASE WHEN attempts >= :maxAttempts THEN 'SCAN_ATTEMPTS_EXHAUSTED'
                                            ELSE 'SCAN_RETRY_SCHEDULED' END,
                       last_error = 'lease expired',
                       next_attempt_at = clock_timestamp()
                           + least(make_interval(secs => :baseSeconds) * power(2, attempts),
                                   make_interval(secs => :maxSeconds))
                             * (0.8 + random() * 0.4),
                       lease_token       = NULL,
                       lease_holder      = NULL,
                       lease_expires_at  = NULL,
                       status_changed_at = clock_timestamp(),
                       updated_at        = clock_timestamp(),
                       version           = version + 1
                 WHERE status IN (%s)
                   AND lease_expires_at <= clock_timestamp()
                RETURNING status = 'FAILED_FINAL' AS given_up, size_bytes, %s AS lag_seconds
                """.formatted(FileStatusSql.LEASED, LAG_SECONDS))
                .param("maxAttempts", maxAttempts)
                .param("baseSeconds", seconds(baseBackoff))
                .param("maxSeconds", seconds(maxBackoff))
                .query((row, index) -> new Reclaimed(row.getBoolean("given_up"), row.getLong("size_bytes"),
                        row.getDouble("lag_seconds")))
                .list();

        reclaimed.stream()
                .filter(Reclaimed::givenUp)
                .forEach(file -> completed(PublicStatus.FAILED, file.sizeBytes(), file.lagSeconds()));
        return reclaimed.size();
    }

    /**
     * A file reached its final state — available, or blocked for good. What it
     * waited from its deposit is its lag, like a message's in a broker; its
     * bytes go into the throughput, which is measured by size because the
     * analysis time grows with it.
     */
    private void completed(PublicStatus outcome, long sizeBytes, double lagSeconds) {
        lagOf(outcome).record(Duration.ofNanos((long) (lagSeconds * 1_000_000_000L)));
        bytesOf(outcome).increment(sizeBytes);
    }

    private Timer lagOf(PublicStatus outcome) {
        return Timer.builder("praxedo.pipeline.lag")
                .description("From deposit to final state (available or blocked), per file: the lag each file saw")
                .tag("outcome", outcome.name())
                .publishPercentileHistogram()
                .serviceLevelObjectives(SLO_TIME_TO_VERDICT)
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofHours(1))
                .register(registry);
    }

    private Counter bytesOf(PublicStatus outcome) {
        return Counter.builder("praxedo.pipeline.completed.bytes")
                .description("Bytes of the files brought to a final state: the throughput, by size")
                .baseUnit("bytes")
                .tag("outcome", outcome.name())
                .register(registry);
    }

    private record Reclaimed(boolean givenUp, long sizeBytes, double lagSeconds) {
    }

    /**
     * A database the worker cannot reach is not a lost race, and not a
     * statement about the file: it becomes the port's
     * {@link WorkQueueUnavailableException}, which the worker knows how to wait
     * out. Any other failure — a constraint, a bad statement — stays what it is:
     * a defect.
     */
    private static <T> T reachable(String operation, Supplier<T> statement) {
        try {
            return statement.get();
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException unreachable) {
            throw new WorkQueueUnavailableException("Work queue unavailable during " + operation, unreachable);
        }
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0d;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }
}
