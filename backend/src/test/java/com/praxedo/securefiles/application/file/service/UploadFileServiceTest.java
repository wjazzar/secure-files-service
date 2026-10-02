package com.praxedo.securefiles.application.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.common.port.out.TransactionRunner;
import com.praxedo.securefiles.application.file.exception.UploadRefusedException;
import com.praxedo.securefiles.application.file.model.FileQuery;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.application.file.model.UploadCommand;
import com.praxedo.securefiles.application.file.model.UploadLimits;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.QuarantineWriter;
import com.praxedo.securefiles.application.idempotency.port.out.IdempotencyStore;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.StorageArea;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.testsupport.SettableClock;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.TricklingInputStream;
import com.praxedo.securefiles.testsupport.Leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The upload use case, with every collaborator faked — no container, no
 * framework, milliseconds per test.
 *
 * <p>What it proves is the <em>order</em> of things and what survives each
 * failure: nothing is read before the headers are accepted, nothing is
 * referenced before it is stored, and nothing is left referenced when the
 * upload fails.
 */
class UploadFileServiceTest {

    private static final long MAX_SIZE = 10_000;
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final OwnerId OWNER = new OwnerId("alice");
    /** 60 s whatever the size: enough to reason about in the tests below. */
    private static final Duration TRANSFER_DEADLINE = Duration.ofSeconds(60);

    private final List<String> events = new ArrayList<>();
    private FakeCatalog catalog;
    private FakeQuarantine quarantine;
    private FakeIdempotency idempotency;
    private UploadFileService service;
    private final SettableClock clock = new SettableClock(NOW);

    @BeforeEach
    void wire() {
        catalog = new FakeCatalog();
        quarantine = new FakeQuarantine();
        idempotency = new FakeIdempotency();
        service = new UploadFileService(catalog, quarantine, idempotency, new DirectTransactions(),
                new UploadLimits(MAX_SIZE, 3, 8, Duration.ZERO, Duration.ofHours(24),
                        Leases.fixed(TRANSFER_DEADLINE)), clock);
    }

    @Nested
    @DisplayName("the nominal path")
    class Nominal {

        @Test
        @DisplayName("the object is stored first, the row committed second — never the other way round")
        void stores_the_bytes_before_referencing_them() {
            service.upload(command("report.pdf", 1_000), TestContent.generated(1_000));

            assertThat(events).containsExactly("write", "insert");
        }

        @Test
        void the_file_records_the_digest_and_size_of_the_bytes_received() {
            StoredFile file = service.upload(command("report.pdf", 5_000), TestContent.generated(5_000));

            assertThat(file.status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(file.area()).isEqualTo(StorageArea.QUARANTINE);
            assertThat(file.sizeBytes()).isEqualTo(5_000);
            assertThat(file.sha256().value()).isEqualTo(TestContent.digestOfGenerated(5_000));
            assertThat(file.uploadedAt()).isEqualTo(NOW);
            assertThat(quarantine.stored.get(file.objectKey().value())).isEqualTo(TestContent.digestOfGenerated(5_000));
        }

        @Test
        @DisplayName("the type comes from the bytes, whatever the name says")
        void the_type_is_detected_from_the_content() {
            InputStream pdf = new SequenceInputStream(
                    new java.io.ByteArrayInputStream("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)),
                    TestContent.generated(91));

            StoredFile file = service.upload(command("setup.exe", 100), pdf);

            assertThat(file.contentType().value()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("the storage key is the generated id, never the supplied name")
        void the_key_is_never_the_name() {
            StoredFile file = service.upload(command("../../etc/passwd", 100), TestContent.generated(100));

            assertThat(file.objectKey().value()).isEqualTo(file.id().value().toString());
            assertThat(quarantine.stored.keySet()).allSatisfy(key -> assertThat(key).doesNotContain("passwd"));
        }
    }

    @Nested
    @DisplayName("refused before the body is read")
    class RefusedOnHeaders {

        @Test
        void an_empty_file_is_refused() {
            assertThatExceptionOfType(UploadRefusedException.EmptyFile.class)
                    .isThrownBy(() -> service.upload(command("empty.txt", 0), untouchable()));
            assertThat(events).isEmpty();
        }

        @Test
        @DisplayName("an oversized file is refused on its declared length, without reading a byte")
        void an_oversized_file_is_refused_without_reading_it() {
            assertThatExceptionOfType(UploadRefusedException.TooLarge.class)
                    .isThrownBy(() -> service.upload(command("huge.bin", MAX_SIZE + 1), untouchable()))
                    .satisfies(refused -> assertThat(refused.maxSizeBytes()).isEqualTo(MAX_SIZE));
            assertThat(events).isEmpty();
        }

        @Test
        @DisplayName("above the pending-work threshold, uploads are turned away rather than queued")
        void admission_is_refused_when_the_queue_is_full() {
            catalog.pendingFiles = 3;

            assertThatExceptionOfType(UploadRefusedException.TooManyPending.class)
                    .isThrownBy(() -> service.upload(command("late.bin", 10), untouchable()));
            assertThat(events).isEmpty();
        }
    }

    @Nested
    @DisplayName("a body that lies about its length")
    class LyingBodies {

        @Test
        @DisplayName("a body shorter than announced keeps nothing: no object, no row, key released")
        void a_short_body_leaves_nothing_behind() {
            assertThatExceptionOfType(UploadRefusedException.LengthMismatch.class)
                    .isThrownBy(() -> service.upload(command("cut.bin", 1_000, "key-12345678"),
                            TestContent.generated(400)));

            assertThat(quarantine.stored).isEmpty();
            assertThat(catalog.files).isEmpty();
            assertThat(idempotency.released).containsExactly("key-12345678");
        }

        @Test
        void a_body_longer_than_announced_is_refused_too() {
            assertThatExceptionOfType(UploadRefusedException.LengthMismatch.class)
                    .isThrownBy(() -> service.upload(command("long.bin", 1_000), TestContent.generated(5_000)));

            assertThat(quarantine.stored).isEmpty();
            assertThat(catalog.files).isEmpty();
        }
    }

    /**
     * A client that never quite goes silent: the connector's read timeout,
     * which measures silence, never fires. R-007 of the review.
     */
    @Nested
    @DisplayName("a body that trickles")
    class TricklingBodies {

        @Test
        @DisplayName("a body still arriving at its deadline is refused as too slow: no object, no row, key released")
        void a_body_past_its_deadline_is_refused() {
            // 10 bytes every 5 s: 1 000 bytes would take 500 s, the deadline is 60 s.
            InputStream trickling = new TricklingInputStream(TestContent.generated(1_000), 10, clock,
                    Duration.ofSeconds(5));

            assertThatExceptionOfType(UploadRefusedException.TooSlow.class)
                    .isThrownBy(() -> service.upload(command("slow.bin", 1_000, "key-12345678"), trickling));

            assertThat(quarantine.stored).isEmpty();
            assertThat(quarantine.discarded).hasSize(1);
            assertThat(catalog.files).isEmpty();
            assertThat(idempotency.released).containsExactly("key-12345678");
        }

        @Test
        @DisplayName("a slow body that arrives within its deadline is accepted: the bound is on the whole, not the pace")
        void a_slow_body_in_time_is_accepted() {
            // 100 bytes every 5 s: 1 000 bytes in 50 s, under the 60 s deadline.
            InputStream slow = new TricklingInputStream(TestContent.generated(1_000), 100, clock,
                    Duration.ofSeconds(5));

            StoredFile file = service.upload(command("slow-but-fine.bin", 1_000), slow);

            assertThat(file.status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(file.sha256().value()).isEqualTo(TestContent.digestOfGenerated(1_000));
        }
    }

    @Nested
    @DisplayName("a commit that fails after the bytes are stored")
    class CommitFailure {

        @Test
        @DisplayName("the stored object is removed, the key released, the failure reported")
        void the_orphan_is_cleaned_up() {
            catalog.failOnInsert = true;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.upload(command("doomed.bin", 100, "key-12345678"),
                            TestContent.generated(100)));

            assertThat(quarantine.stored).isEmpty();
            assertThat(quarantine.discarded).hasSize(1);
            assertThat(idempotency.released).containsExactly("key-12345678");
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        void a_new_key_is_completed_with_the_new_file() {
            StoredFile file = service.upload(command("a.bin", 100, "key-12345678"), TestContent.generated(100));

            assertThat(idempotency.completed).containsEntry("key-12345678", file.id());
        }

        @Test
        @DisplayName("a replayed key returns the first file, and the body is not even read")
        void a_replay_returns_the_same_file() {
            StoredFile first = service.upload(command("a.bin", 100, "key-12345678"), TestContent.generated(100));
            events.clear();
            idempotency.next = new IdempotencyStore.Reservation.Replay(first.id());

            StoredFile again = service.upload(command("a.bin", 100, "key-12345678"), untouchable());

            assertThat(again.id()).isEqualTo(first.id());
            assertThat(events).isEmpty();
        }

        @Test
        void a_key_still_in_progress_is_answered_as_such() {
            idempotency.next = new IdempotencyStore.Reservation.InProgress();

            assertThatExceptionOfType(UploadRefusedException.InProgress.class)
                    .isThrownBy(() -> service.upload(command("a.bin", 100, "key-12345678"), untouchable()));
        }

        @Test
        void a_key_reused_for_another_request_is_refused() {
            idempotency.next = new IdempotencyStore.Reservation.Reused();

            assertThatExceptionOfType(UploadRefusedException.KeyReused.class)
                    .isThrownBy(() -> service.upload(command("b.bin", 100, "key-12345678"), untouchable()));
        }

        @Test
        @DisplayName("the fingerprint tells two requests apart by name and by length")
        void the_fingerprint_depends_on_name_and_length() {
            String reference = UploadFileService.fingerprint(command("a.bin", 100));

            assertThat(UploadFileService.fingerprint(command("a.bin", 100))).isEqualTo(reference);
            assertThat(UploadFileService.fingerprint(command("b.bin", 100))).isNotEqualTo(reference);
            assertThat(UploadFileService.fingerprint(command("a.bin", 101))).isNotEqualTo(reference);
            assertThat(reference).hasSize(64);
        }
    }

    // ── helpers and fakes ───────────────────────────────────────────────

    private static UploadCommand command(String name, long size) {
        return new UploadCommand(OWNER, FileName.sanitised(name), size, Optional.empty());
    }

    private static UploadCommand command(String name, long size, String key) {
        return new UploadCommand(OWNER, FileName.sanitised(name), size, Optional.of(key));
    }

    /** A body that fails the test if anyone reads it. */
    private static InputStream untouchable() {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("The body must not be read");
            }
        };
    }

    private final class FakeQuarantine implements QuarantineWriter {
        final Map<String, String> stored = new HashMap<>();
        final List<String> discarded = new ArrayList<>();

        @Override
        public void write(ObjectKey key, InputStream content, long sizeBytes) {
            events.add("write");
            try {
                // Like the real storage: read exactly the announced length, fail on a short body.
                byte[] announced = content.readNBytes((int) sizeBytes);
                if (announced.length < sizeBytes) {
                    throw new IllegalStateException("The request content has fewer bytes than announced");
                }
                stored.put(key.value(), TestContent.digestOf(new java.io.ByteArrayInputStream(announced)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void discard(ObjectKey key) {
            discarded.add(key.value());
            stored.remove(key.value());
        }
    }

    private final class FakeCatalog implements FileCatalog {
        final Map<FileId, StoredFile> files = new HashMap<>();
        long pendingFiles;
        boolean failOnInsert;

        @Override
        public StoredFile insert(StoredFile file) {
            events.add("insert");
            if (failOnInsert) {
                throw new IllegalStateException("database unavailable");
            }
            files.put(file.id(), file);
            return file;
        }

        @Override
        public Optional<StoredFile> findById(FileId id) {
            return Optional.ofNullable(files.get(id));
        }

        @Override
        public Optional<StoredFile> findOwnedBy(FileId id, OwnerId owner) {
            return findById(id).filter(file -> file.owner().equals(owner));
        }

        @Override
        public long countPendingFiles() {
            return pendingFiles;
        }

        @Override
        public PageResult<StoredFile> findPage(FileQuery query, PageQuery page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<FileStatus, Long> countByStatus(FileQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, FileStatus> statusesByObjectKey(Collection<String> objectKeys) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeIdempotency implements IdempotencyStore {
        IdempotencyStore.Reservation next = new IdempotencyStore.Reservation.Granted();
        final Map<String, FileId> completed = new HashMap<>();
        final List<String> released = new ArrayList<>();

        @Override
        public Reservation reserve(OwnerId owner, String key, String fingerprint, Duration ttl) {
            return next;
        }

        @Override
        public void complete(OwnerId owner, String key, FileId file) {
            completed.put(key, file);
        }

        @Override
        public void release(OwnerId owner, String key) {
            released.add(key);
        }

        @Override
        public int purge(Duration abandonedAfter) {
            return 0;
        }
    }

    private static final class DirectTransactions implements TransactionRunner {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }
}
