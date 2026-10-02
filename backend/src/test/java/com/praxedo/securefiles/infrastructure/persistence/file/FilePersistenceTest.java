package com.praxedo.securefiles.infrastructure.persistence.file;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.Lease;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StorageArea;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.domain.file.valueobject.Sha256;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.infrastructure.persistence.common.config.DataSourceConfiguration;
import com.praxedo.securefiles.infrastructure.persistence.file.mapper.StoredFileRowMapper;
import com.praxedo.securefiles.testsupport.FileFixtures;
import com.praxedo.securefiles.testsupport.PostgresTestcontainer;
import com.praxedo.securefiles.testsupport.Leases;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two halves of persistence, exercised together against a real PostgreSQL.
 *
 * <p>The test runs the real wiring on purpose — the JPA catalogue <em>and</em>
 * the JDBC work queue — because the risk of mixing an ORM with hand-written
 * statements is exactly that they disagree. Everything proved here is a
 * property of PostgreSQL, so no doubled test could stand in for it.
 */
@SpringBootTest
class FilePersistenceTest extends PostgresTestcontainer {

    private static final LeaseTerms LEASE = Leases.fixed(Duration.ofMinutes(10));
    private static final Duration PROMOTION_LEASE = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    FileCatalog catalog;

    @Autowired
    FileWorkQueue workQueue;

    @Autowired
    HikariDataSource apiPool;

    @org.springframework.beans.factory.annotation.Value("${praxedo.worker.concurrency}")
    int workerConcurrency;

    @Autowired
    @Qualifier(DataSourceConfiguration.QUEUE)
    HikariDataSource queuePool;

    @Nested
    @DisplayName("storing and reading back")
    class Roundtrip {

        @Test
        @DisplayName("a reason this version does not know reads as none, on both paths: the status still decides")
        void an_unknown_reason_does_not_break_a_read() {
            StoredFile file = received("ancien.pdf");
            catalog.insert(file);
            jdbc.sql("UPDATE stored_file SET status_reason = 'RETIRED_REASON' WHERE id = :id")
                    .param("id", file.id().value()).update();

            StoredFile throughJpa = catalog.findById(file.id()).orElseThrow();
            StoredFile throughJdbc = jdbc.sql("SELECT * FROM stored_file WHERE id = :id")
                    .param("id", file.id().value()).query(StoredFileRowMapper.INSTANCE).single();

            assertThat(throughJpa.reason()).isEmpty();
            assertThat(throughJdbc.reason()).isEmpty();
            assertThat(throughJpa.status()).isEqualTo(throughJdbc.status()).isEqualTo(FileStatus.AWAITING_SCAN);
        }

        @Test
        void a_stored_file_comes_back_identical() {
            StoredFile file = received("rapport.pdf");
            catalog.insert(file);

            StoredFile reloaded = catalog.findById(file.id()).orElseThrow();

            assertThat(reloaded.id()).isEqualTo(file.id());
            assertThat(reloaded.filename()).isEqualTo(file.filename());
            assertThat(reloaded.sha256()).isEqualTo(file.sha256());
            assertThat(reloaded.sizeBytes()).isEqualTo(file.sizeBytes());
            assertThat(reloaded.status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(reloaded.area()).isEqualTo(StorageArea.QUARANTINE);
            assertThat(reloaded.isDownloadable()).isFalse();
        }

        @Test
        @DisplayName("another owner's file is simply not found")
        void a_file_is_only_visible_to_its_owner() {
            StoredFile file = received("rapport.pdf", new OwnerId("alice"));
            catalog.insert(file);

            assertThat(catalog.findOwnedBy(file.id(), new OwnerId("alice"))).isPresent();
            assertThat(catalog.findOwnedBy(file.id(), new OwnerId("bob"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("the atomic claim")
    class Claim {

        @Test
        void claiming_takes_the_lease_and_counts_the_attempt() {
            StoredFile file = received("rapport.pdf");
            catalog.insert(file);
            LeaseToken token = LeaseToken.random();

            StoredFile claimed = workQueue
                    .claimNextDue("worker-1", token, LEASE, MAX_ATTEMPTS).orElseThrow();

            assertThat(claimed.id()).isEqualTo(file.id());
            assertThat(claimed.status()).isEqualTo(FileStatus.SCANNING);
            assertThat(claimed.attempts()).isEqualTo(1);
            assertThat(claimed.currentLease()).map(Lease::token).contains(token);
            assertThat(claimed.currentLease().orElseThrow().expiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("the SQL claim produces exactly what the domain transition describes")
        void the_statement_and_the_domain_agree() {
            StoredFile file = received("rapport.pdf");
            catalog.insert(file);
            LeaseToken token = LeaseToken.random();

            StoredFile fromSql = workQueue
                    .claimNextDue("worker-1", token, LEASE, MAX_ATTEMPTS).orElseThrow();
            StoredFile fromDomain = catalog.findById(file.id()).orElseThrow();

            // Same rule, written twice — in Java and in SQL. This test is what
            // keeps the two from drifting apart.
            StoredFile expected = file.claimedBy(token, "worker-1", fromSql.statusChangedAt(), LEASE.forSize(file.sizeBytes()));
            assertThat(fromSql.status()).isEqualTo(expected.status());
            assertThat(fromSql.attempts()).isEqualTo(expected.attempts());
            assertThat(fromSql.currentLease()).map(Lease::token).contains(token);
            assertThat(fromDomain.status()).isEqualTo(FileStatus.SCANNING);
        }

        @Test
        @DisplayName("the lease is sized by the database from the claimed file: 30 s + 2 MiB × 10 s = 50 s")
        void the_lease_grows_with_the_file() {
            catalog.insert(StoredFile.received(FileId.random(), FileFixtures.OWNER, FileName.sanitised("big.bin"),
                    ContentType.OCTET_STREAM, 2L * 1024 * 1024, randomDigest(), Instant.now()));
            LeaseTerms terms = new LeaseTerms(Duration.ofSeconds(30), Duration.ofSeconds(10));

            StoredFile claimed = workQueue
                    .claimNextDue("worker-1", LeaseToken.random(), terms, MAX_ATTEMPTS).orElseThrow();

            Duration granted = Duration.between(
                    claimed.statusChangedAt(), claimed.currentLease().orElseThrow().expiresAt());
            assertThat(granted).isBetween(Duration.ofMillis(49_900), Duration.ofMillis(50_100));
            assertThat(terms.forSize(claimed.sizeBytes())).isEqualTo(Duration.ofSeconds(50));
        }

        @Test
        void nothing_is_claimed_when_nothing_is_due() {
            assertThat(workQueue.claimNextDue("worker-1", LeaseToken.random(), LEASE, MAX_ATTEMPTS))
                    .isEmpty();
        }

        @Test
        @DisplayName("work whose attempts are exhausted is never picked up again")
        void exhausted_work_is_left_alone() {
            StoredFile file = received("poison.bin");
            catalog.insert(file);
            setAttempts(file.id(), MAX_ATTEMPTS);

            assertThat(workQueue.claimNextDue("worker-1", LeaseToken.random(), LEASE, MAX_ATTEMPTS))
                    .isEmpty();
        }

        @Test
        void work_scheduled_for_later_waits_its_turn() {
            StoredFile file = received("rapport.pdf");
            catalog.insert(file);
            scheduleIn(file.id(), Duration.ofMinutes(5));

            assertThat(workQueue.claimNextDue("worker-1", LeaseToken.random(), LEASE, MAX_ATTEMPTS))
                    .isEmpty();
        }

        @Test
        @DisplayName("eight workers competing for eight files take eight different ones")
        void concurrent_workers_never_take_the_same_file() throws Exception {
            int workers = 8;
            IntStream.range(0, workers).forEach(index -> catalog.insert(received("file-" + index + ".bin")));

            List<Optional<StoredFile>> claims;
            try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
                List<Callable<Optional<StoredFile>>> tasks = IntStream.range(0, workers)
                        .<Callable<Optional<StoredFile>>>mapToObj(index -> () -> workQueue
                                .claimNextDue("worker-" + index, LeaseToken.random(), LEASE, MAX_ATTEMPTS))
                        .toList();

                claims = pool.invokeAll(tasks).stream().map(FilePersistenceTest::get).toList();
            }

            List<FileId> claimedIds = claims.stream().flatMap(Optional::stream).map(StoredFile::id).toList();
            assertThat(claimedIds).hasSize(workers);
            assertThat(claimedIds).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("writing a verdict")
    class Verdicts {

        @Test
        void a_clean_verdict_moves_the_file_to_promotion_and_extends_the_lease() {
            StoredFile claimed = insertAndClaim("rapport.pdf");
            LeaseToken claim = claimed.currentLease().orElseThrow().token();
            Instant leaseBefore = claimed.currentLease().orElseThrow().expiresAt();

            boolean won = workQueue.writeVerdict(
                    claimed.scanned(clean(claimed.sha256()), Instant.now()), claim, PROMOTION_LEASE);

            assertThat(won).isTrue();
            StoredFile reloaded = catalog.findById(claimed.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(FileStatus.PROMOTING);
            assertThat(reloaded.isDownloadable()).isFalse();
            assertThat(reloaded.currentLease()).map(Lease::token).contains(claim);
            assertThat(reloaded.currentLease().orElseThrow().expiresAt()).isAfter(leaseBefore);
            assertThat(reloaded.scan().orElseThrow().result()).isEqualTo(ScanResult.CLEAN);
        }

        @Test
        void a_threat_blocks_the_file_and_releases_the_lease() {
            StoredFile claimed = insertAndClaim("eicar.txt");
            LeaseToken claim = claimed.currentLease().orElseThrow().token();

            boolean won = workQueue.writeVerdict(
                    claimed.scanned(infected(claimed.sha256()), Instant.now()), claim, PROMOTION_LEASE);

            assertThat(won).isTrue();
            StoredFile reloaded = catalog.findById(claimed.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(FileStatus.INFECTED);
            assertThat(reloaded.isTerminal()).isTrue();
            assertThat(reloaded.currentLease()).isEmpty();
            assertThat(reloaded.scan().orElseThrow().threat()).contains("Eicar-Test-Signature");
        }

        @Test
        @DisplayName("a worker whose lease was taken over cannot overwrite the fresh verdict")
        void the_zombie_worker_writes_nothing() {
            StoredFile claimed = insertAndClaim("rapport.pdf");
            LeaseToken staleClaim = claimed.currentLease().orElseThrow().token();

            // The reaper hands the work to someone else, who claims it again.
            expireLease(claimed.id());
            workQueue.reclaimExpiredLeases(MAX_ATTEMPTS, Duration.ZERO, Duration.ofMinutes(1));
            StoredFile reclaimed = workQueue
                    .claimNextDue("worker-2", LeaseToken.random(), LEASE, MAX_ATTEMPTS).orElseThrow();

            // The frozen worker wakes up and tries to write its verdict.
            boolean won = workQueue.writeVerdict(
                    reclaimed.scanned(clean(reclaimed.sha256()), Instant.now()), staleClaim, PROMOTION_LEASE);

            assertThat(won).isFalse();
            assertThat(catalog.findById(claimed.id()).orElseThrow().status())
                    .isEqualTo(FileStatus.SCANNING);
        }

        @Test
        void a_verdict_written_after_the_lease_expired_is_refused() {
            StoredFile claimed = insertAndClaim("rapport.pdf");
            LeaseToken claim = claimed.currentLease().orElseThrow().token();
            expireLease(claimed.id());

            boolean won = workQueue.writeVerdict(
                    claimed.scanned(clean(claimed.sha256()), Instant.now()), claim, PROMOTION_LEASE);

            assertThat(won).isFalse();
        }
    }

    @Nested
    @DisplayName("promotion")
    class Promotion {

        @Test
        void the_compare_and_set_makes_the_file_available() {
            StoredFile promoting = promoting("rapport.pdf");
            LeaseToken claim = promoting.currentLease().orElseThrow().token();

            boolean won = workQueue.markAvailable(promoting.promoted(Instant.now()), claim);

            assertThat(won).isTrue();
            StoredFile reloaded = catalog.findById(promoting.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(FileStatus.AVAILABLE);
            assertThat(reloaded.area()).isEqualTo(StorageArea.SERVABLE);
            assertThat(reloaded.isDownloadable()).isTrue();
            assertThat(reloaded.currentLease()).isEmpty();
        }

        @Test
        void a_promotion_holding_the_wrong_claim_changes_nothing() {
            StoredFile promoting = promoting("rapport.pdf");

            boolean won = workQueue.markAvailable(promoting.promoted(Instant.now()), LeaseToken.random());

            assertThat(won).isFalse();
            assertThat(catalog.findById(promoting.id()).orElseThrow().isDownloadable()).isFalse();
        }
    }

    @Nested
    @DisplayName("failures and recovery")
    class Recovery {

        @Test
        void a_technical_failure_sends_the_work_back_with_a_delay() {
            StoredFile claimed = insertAndClaim("rapport.pdf");
            LeaseToken claim = claimed.currentLease().orElseThrow().token();

            boolean written = workQueue.writeTechnicalFailure(
                    claimed.technicalFailure(MAX_ATTEMPTS, Instant.now()), claim,
                    Duration.ofMinutes(2), "antivirus timeout");

            assertThat(written).isTrue();
            StoredFile reloaded = catalog.findById(claimed.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(FileStatus.RETRY_WAIT);
            assertThat(reloaded.currentLease()).isEmpty();
            assertThat(nextAttempt(reloaded.id())).isAfter(Instant.now().plusSeconds(60));
            // Not due yet, so nobody picks it up.
            assertThat(workQueue.claimNextDue("worker-1", LeaseToken.random(), LEASE, MAX_ATTEMPTS)).isEmpty();
        }

        @Test
        @DisplayName("an expired lease goes back to the queue, and the attempt stays spent")
        void the_reaper_requeues_abandoned_work() {
            StoredFile claimed = insertAndClaim("rapport.pdf");
            expireLease(claimed.id());

            int reclaimed = workQueue.reclaimExpiredLeases(MAX_ATTEMPTS, Duration.ZERO, Duration.ofMinutes(1));

            assertThat(reclaimed).isEqualTo(1);
            StoredFile reloaded = catalog.findById(claimed.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(FileStatus.RETRY_WAIT);
            assertThat(reloaded.attempts()).isEqualTo(1);
            assertThat(reloaded.currentLease()).isEmpty();
        }

        @Test
        void work_still_within_its_lease_is_left_alone() {
            insertAndClaim("rapport.pdf");

            assertThat(workQueue.reclaimExpiredLeases(MAX_ATTEMPTS, Duration.ZERO, Duration.ofMinutes(1)))
                    .isZero();
        }

        @Test
        @DisplayName("when the attempts are spent, the reaper ends the work instead of looping")
        void exhausted_work_becomes_a_final_failure() {
            StoredFile claimed = insertAndClaim("poison.bin");
            setAttempts(claimed.id(), MAX_ATTEMPTS);
            expireLease(claimed.id());

            workQueue.reclaimExpiredLeases(MAX_ATTEMPTS, Duration.ZERO, Duration.ofMinutes(1));

            StoredFile reloaded = catalog.findById(claimed.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(FileStatus.FAILED_FINAL);
            assertThat(reloaded.isTerminal()).isTrue();
        }

        @Test
        void a_clean_shutdown_gives_the_work_and_the_attempt_back() {
            StoredFile claimed = insertAndClaim("rapport.pdf");
            LeaseToken claim = claimed.currentLease().orElseThrow().token();

            boolean released = workQueue
                    .releaseOnShutdown(claimed.releasedOnShutdown(Instant.now()), claim);

            assertThat(released).isTrue();
            StoredFile reloaded = catalog.findById(claimed.id()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(reloaded.attempts()).isZero();
            // Immediately available to another worker.
            assertThat(workQueue.claimNextDue("worker-2", LeaseToken.random(), LEASE, MAX_ATTEMPTS))
                    .isPresent();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * The bulkhead between the API and the workers: a burst of requests may take
     * every connection it has, the queue keeps its own.
     */
    @Nested
    @DisplayName("the work queue's own pool")
    class OwnPool {

        @Test
        @DisplayName("⭐ every API connection taken: the worker still claims its file")
        void the_queue_survives_a_drained_api_pool() throws SQLException {
            catalog.insert(received("rapport.pdf"));
            List<Connection> taken = new ArrayList<>();
            try {
                while (taken.size() < apiPool.getMaximumPoolSize()) {
                    taken.add(apiPool.getConnection());
                }

                assertThat(workQueue.claimNextDue("worker-1", LeaseToken.random(), LEASE, MAX_ATTEMPTS)).isPresent();
            } finally {
                for (Connection connection : taken) {
                    connection.close();
                }
            }
        }

        @Test
        @DisplayName("sized on the workers, not on the API: one connection each, and two more")
        void sized_on_the_workers() {
            assertThat(queuePool.getPoolName()).isEqualTo(DataSourceConfiguration.QUEUE);
            assertThat(apiPool.getPoolName()).isEqualTo("api");
            assertThat(queuePool.getMaximumPoolSize()).isEqualTo(workerConcurrency + 2);
            // What pg_stat_activity shows: which pool a waiting session belongs to.
            assertThat(apiPool.getDataSourceProperties()).containsEntry("ApplicationName", "praxedo-api");
            assertThat(queuePool.getDataSourceProperties()).containsEntry("ApplicationName", "praxedo-queue");
        }

        @Test
        @DisplayName("rule B-7 on both pools: a silent database cannot hold a thread for ever")
        void both_pools_wait_for_the_database_within_explicit_timeouts() throws SQLException {
            for (HikariDataSource pool : List.of(apiPool, queuePool)) {
                assertThat(pool.getDataSourceProperties())
                        .containsEntry("connectTimeout", "5")
                        .containsEntry("socketTimeout", "30");
                // Read back from the server: the setting reached PostgreSQL, not only the pool.
                try (Connection connection = pool.getConnection();
                     var statement = connection.createStatement();
                     var result = statement.executeQuery("SHOW statement_timeout")) {
                    result.next();
                    assertThat(result.getString(1)).as(pool.getPoolName()).isEqualTo("15s");
                }
            }
        }
    }

    private StoredFile received(String name) {
        return received(name, FileFixtures.OWNER);
    }

    private StoredFile received(String name, OwnerId owner) {
        return StoredFile.received(FileId.random(), owner, FileName.sanitised(name),
                ContentType.OCTET_STREAM, 2_048L, randomDigest(), Instant.now());
    }

    private StoredFile insertAndClaim(String name) {
        catalog.insert(received(name));
        return workQueue.claimNextDue("worker-1", LeaseToken.random(), LEASE, MAX_ATTEMPTS).orElseThrow();
    }

    private StoredFile promoting(String name) {
        StoredFile claimed = insertAndClaim(name);
        LeaseToken claim = claimed.currentLease().orElseThrow().token();
        workQueue.writeVerdict(claimed.scanned(clean(claimed.sha256()), Instant.now()), claim, PROMOTION_LEASE);
        return catalog.findById(claimed.id()).orElseThrow();
    }

    private static ScanVerdict clean(Sha256 content) {
        return new ScanVerdict(ScanResult.CLEAN, null, "ClamAV", "1.4.6", "28098",
                content, Instant.now(), Duration.ofMillis(120));
    }

    private static ScanVerdict infected(Sha256 content) {
        return new ScanVerdict(ScanResult.INFECTED, "Eicar-Test-Signature", "ClamAV", "1.4.6", "28098",
                content, Instant.now(), Duration.ofMillis(95));
    }

    private static Sha256 randomDigest() {
        StringBuilder digest = new StringBuilder(64);
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 64; i++) {
            digest.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return new Sha256(digest.toString());
    }

    private void setAttempts(FileId id, int attempts) {
        jdbc.sql("UPDATE stored_file SET attempts = :attempts WHERE id = :id")
                .param("attempts", attempts).param("id", id.value()).update();
    }

    private void scheduleIn(FileId id, Duration delay) {
        jdbc.sql("UPDATE stored_file SET next_attempt_at = clock_timestamp() + make_interval(secs => :secs) WHERE id = :id")
                .param("secs", (double) delay.toSeconds()).param("id", id.value()).update();
    }

    private void expireLease(FileId id) {
        jdbc.sql("UPDATE stored_file SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = :id")
                .param("id", id.value()).update();
    }

    private Instant nextAttempt(FileId id) {
        return jdbc.sql("SELECT next_attempt_at FROM stored_file WHERE id = :id")
                .param("id", id.value())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
