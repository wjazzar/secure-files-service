package com.praxedo.securefiles.application.file.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.model.WorkerSettings;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.domain.file.model.StatusReason;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.Sha256;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.testsupport.InMemoryWorkQueue;
import com.praxedo.securefiles.testsupport.InMemoryWorkerStorage;
import com.praxedo.securefiles.testsupport.ScriptedScanner;
import com.praxedo.securefiles.testsupport.SettableClock;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.TricklingInputStream;
import com.praxedo.securefiles.testsupport.Leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The worker, with the queue, the storage and the engine faked — so that every
 * way they can misbehave can be staged, one at a time.
 */
class FileScanServiceTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final byte[] CONTENT = "a perfectly ordinary document".getBytes(StandardCharsets.UTF_8);

    private InMemoryWorkQueue queue;
    private InMemoryWorkerStorage storage;
    private ScriptedScanner scanner;
    private FileScanService worker;

    @BeforeEach
    void wire() {
        queue = new InMemoryWorkQueue();
        storage = new InMemoryWorkerStorage();
        scanner = new ScriptedScanner();
        WorkerSettings settings = new WorkerSettings(Leases.fixed(Duration.ofMinutes(10)), Leases.fixed(Duration.ofMinutes(15)),
                MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofMinutes(1));
        Clock clock = Clock.systemUTC();
        worker = new FileScanService(queue, storage, scanner, new FilePromotionService(queue, storage, settings, clock),
                settings, clock, "worker-under-test");
    }

    @Nested
    @DisplayName("verdicts")
    class Verdicts {

        @Test
        @DisplayName("a clean file ends up available, copied byte for byte, its source removed")
        void a_clean_file_becomes_available() {
            StoredFile file = uploaded("report.pdf");

            assertThat(worker.processNext()).isTrue();

            StoredFile done = queue.current(file.id());
            assertThat(done.status()).isEqualTo(FileStatus.AVAILABLE);
            assertThat(done.isDownloadable()).isTrue();
            assertThat(storage.servable.get(file.objectKey().value())).isEqualTo(CONTENT);
            assertThat(storage.quarantine).doesNotContainKey(file.objectKey().value());
        }

        @Test
        @DisplayName("an infected file is blocked, never copied, and kept in quarantine as evidence")
        void an_infected_file_is_blocked() {
            scanner.next = ScanResult.INFECTED;
            scanner.detail = "Eicar-Test-Signature";
            StoredFile file = uploaded("eicar.txt");

            worker.processNext();

            StoredFile blocked = queue.current(file.id());
            assertThat(blocked.status()).isEqualTo(FileStatus.INFECTED);
            assertThat(blocked.scan().orElseThrow().threat()).contains("Eicar-Test-Signature");
            assertThat(storage.servable).isEmpty();
            assertThat(storage.quarantine).containsKey(file.objectKey().value());
        }

        @Test
        void an_unscannable_file_is_blocked_with_its_reason() {
            scanner.next = ScanResult.UNSCANNABLE;
            scanner.detail = "Heuristics.Limits.Exceeded.MaxScanSize";
            StoredFile file = uploaded("bomb.zip");

            worker.processNext();

            assertThat(queue.current(file.id()).status()).isEqualTo(FileStatus.UNSCANNABLE);
            assertThat(queue.current(file.id()).reason()).contains(StatusReason.EXCEEDS_SCANNER_SIZE_LIMIT);
            assertThat(storage.servable).isEmpty();
        }
    }

    @Nested
    @DisplayName("an engine that lets the service down")
    class EngineFailures {

        @Test
        @DisplayName("engine down: nothing is claimed, so the outage costs no attempt")
        void a_down_engine_consumes_no_attempt() {
            scanner.available = false;
            StoredFile file = uploaded("waiting.pdf");

            assertThat(worker.processNext()).isFalse();

            assertThat(queue.current(file.id()).status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(queue.current(file.id()).attempts()).isZero();
            assertThat(scanner.scans).isZero();
        }

        @Test
        @DisplayName("an engine failure sends the file back to the queue — a failure is never a verdict")
        void a_failing_engine_schedules_a_retry() {
            scanner.failing = true;
            StoredFile file = uploaded("unlucky.pdf");

            worker.processNext();

            StoredFile retried = queue.current(file.id());
            assertThat(retried.status()).isEqualTo(FileStatus.RETRY_WAIT);
            assertThat(retried.scan()).isEmpty();
            assertThat(retried.attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("after the last attempt the file is given up on — as FAILED, never as clean or infected")
        void the_last_failure_is_final() {
            scanner.failing = true;
            StoredFile file = uploaded("doomed.pdf");

            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                worker.processNext();
            }

            assertThat(queue.current(file.id()).status()).isEqualTo(FileStatus.FAILED_FINAL);
            assertThat(queue.current(file.id()).reason()).contains(StatusReason.SCAN_ATTEMPTS_EXHAUSTED);
            assertThat(worker.processNext()).as("nothing left to claim").isFalse();
        }

        @Test
        void a_storage_outage_is_a_retry_too() {
            storage.failOpen = true;
            StoredFile file = uploaded("elsewhere.pdf");

            worker.processNext();

            assertThat(queue.current(file.id()).status()).isEqualTo(FileStatus.RETRY_WAIT);
        }
    }

    /**
     * What left three files in {@code SCANNING} during the capacity campaign
     * {@code scale-1cpu-8w}: a pool drained by a burst of uploads, and an
     * exception nobody caught.
     */
    @Nested
    @DisplayName("a database out of reach")
    class DatabaseOutOfReach {

        @Test
        @DisplayName("a verdict whose first writes find no connection is written again: the analysis is not lost")
        void a_verdict_is_written_again() {
            StoredFile file = uploaded("report.pdf");
            queue.unreachableForWrites(2);

            assertThat(worker.processNext()).isTrue();

            assertThat(queue.current(file.id()).status()).isEqualTo(FileStatus.AVAILABLE);
            assertThat(scanner.scans).isEqualTo(1);
        }

        @Test
        @DisplayName("a database that stays out of reach: nothing escapes, the lease will return the file")
        void nothing_escapes_when_it_stays_out_of_reach() {
            StoredFile file = uploaded("report.pdf");
            queue.unreachableForWrites(Integer.MAX_VALUE);

            assertThatNoException().isThrownBy(worker::processNext);

            StoredFile held = queue.current(file.id());
            assertThat(held.status()).isEqualTo(FileStatus.SCANNING);
            assertThat(held.currentLease()).isPresent();
        }

        @Test
        @DisplayName("no connection to claim with: nothing is taken, the worker waits like on an empty queue")
        void no_claim_without_a_database() {
            StoredFile file = uploaded("report.pdf");
            queue.unreachableForClaims(1);

            assertThat(worker.processNext()).isFalse();

            assertThat(queue.current(file.id()).status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(scanner.scans).isZero();
        }
    }

    @Nested
    @DisplayName("the invariant under stress")
    class Invariant {

        @Test
        @DisplayName("⭐ bytes altered in storage: the clean verdict about them is never recorded")
        void a_verdict_about_other_bytes_is_refused() {
            storage.corruptFromRead = 1;   // the very first read already returns altered bytes
            StoredFile file = uploaded("tampered.pdf");

            worker.processNext();

            StoredFile after = queue.current(file.id());
            assertThat(after.status()).isNotIn(FileStatus.PROMOTING, FileStatus.AVAILABLE);
            assertThat(after.scan()).as("no verdict recorded").isEmpty();
            assertThat(queue.failureReasons()).anySatisfy(reason ->
                    assertThat(reason).contains("does not match its upload digest"));
            assertThat(storage.servable).isEmpty();
        }

        @Test
        @DisplayName("a worker whose lease was taken over writes nothing, and promotes nothing")
        void a_worker_that_lost_its_lease_stops() {
            StoredFile file = uploaded("contested.pdf");
            scanner.duringScan = () -> queue.stealLease(file.id());

            worker.processNext();

            StoredFile after = queue.current(file.id());
            assertThat(after.status()).isEqualTo(FileStatus.SCANNING);
            assertThat(after.currentLease().orElseThrow().holder()).isEqualTo("thief");
            assertThat(storage.servable).isEmpty();
        }

        @Test
        @DisplayName("a clean shutdown mid-analysis hands the file back, attempt included")
        void a_shutdown_releases_the_work_in_progress() {
            StoredFile file = uploaded("interrupted.pdf");
            int[] released = new int[1];
            scanner.duringScan = () -> released[0] = worker.releaseInFlight();

            worker.processNext();

            assertThat(released[0]).isEqualTo(1);
            StoredFile after = queue.current(file.id());
            assertThat(after.status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(after.attempts()).as("the attempt is given back").isZero();
        }
    }

    /**
     * A storage that never quite goes silent: no socket timeout fires, yet the
     * transfer would outlast the lease — after which nothing it produces could
     * be written. R-007 of the review.
     */
    @Nested
    @DisplayName("a transfer that never quite stops")
    class TricklingTransfers {

        private static final Duration SCAN_LEASE = Duration.ofMinutes(10);
        private static final Duration PROMOTION_LEASE = Duration.ofMinutes(15);

        private final SettableClock clock = new SettableClock(Instant.parse("2026-09-30T12:00:00Z"));
        private FileScanService slowWorker;

        @BeforeEach
        void wireWithAClockThatMoves() {
            WorkerSettings settings = new WorkerSettings(Leases.fixed(SCAN_LEASE),
                    Leases.fixed(PROMOTION_LEASE), MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofMinutes(1));
            slowWorker = new FileScanService(queue, storage, scanner,
                    new FilePromotionService(queue, storage, settings, clock), settings, clock, "slow-worker");
        }

        @Test
        @DisplayName("an analysis still reading when its lease ends stops, and the file goes back to the queue at once")
        void the_analysis_stops_at_the_end_of_its_lease() {
            // One byte a minute: the 29 bytes would take 29 minutes, the lease lasts 10.
            storage.quarantineReads = content -> new TricklingInputStream(content, 1, clock, Duration.ofMinutes(1));
            StoredFile file = uploaded("trickling.pdf");

            assertThat(slowWorker.processNext()).isTrue();

            StoredFile after = queue.current(file.id());
            assertThat(after.status()).isEqualTo(FileStatus.RETRY_WAIT);
            assertThat(after.scan()).as("a failure is never a verdict").isEmpty();
            assertThat(queue.failureReasons()).anySatisfy(reason -> assertThat(reason).contains("outlasted its lease"));
            assertThat(clock.instant()).isBefore(Instant.parse("2026-09-30T12:12:00Z"));
        }

        @Test
        @DisplayName("a promotion copy still reading when its lease ends stops: nothing becomes servable")
        void the_promotion_stops_at_the_end_of_its_lease() {
            int[] opened = new int[1];
            // The analysis reads at full speed; the promotion's re-read trickles.
            storage.quarantineReads = content -> ++opened[0] == 1
                    ? content
                    : new TricklingInputStream(content, 1, clock, Duration.ofMinutes(1));
            StoredFile file = uploaded("trickling-copy.pdf");

            slowWorker.processNext();

            StoredFile after = queue.current(file.id());
            assertThat(after.status()).isEqualTo(FileStatus.RETRY_WAIT);
            assertThat(after.isDownloadable()).isFalse();
            assertThat(storage.servable).isEmpty();
            assertThat(queue.failureReasons()).anySatisfy(reason ->
                    assertThat(reason).contains("promotion copy outlasted its lease"));
        }
    }

    @Test
    void nothing_to_do_is_reported_as_such() {
        assertThat(worker.processNext()).isFalse();
    }

    private StoredFile uploaded(String name) {
        StoredFile file = StoredFile.received(FileId.random(), new OwnerId("alice"), FileName.sanitised(name),
                ContentType.OCTET_STREAM, CONTENT.length,
                new Sha256(TestContent.digestOf(new ByteArrayInputStream(CONTENT))), Instant.now());
        queue.add(file);
        storage.quarantine.put(file.objectKey().value(), CONTENT);
        return file;
    }
}
