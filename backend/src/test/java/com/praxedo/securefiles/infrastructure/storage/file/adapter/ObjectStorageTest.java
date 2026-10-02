package com.praxedo.securefiles.infrastructure.storage.file.adapter;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.exception.ObjectMissingException;
import com.praxedo.securefiles.application.file.exception.RangeNotSatisfiableException;
import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.application.file.model.ContentStream;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.infrastructure.storage.common.config.S3Clients;
import com.praxedo.securefiles.infrastructure.storage.common.config.S3Clients.Retries;
import com.praxedo.securefiles.infrastructure.storage.common.config.StorageProperties;
import com.praxedo.securefiles.testsupport.SeaweedFsContainer;
import com.praxedo.securefiles.testsupport.TestContent;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The three storage adapters, against a real SeaweedFS loaded with the real
 * identity file.
 *
 * <p>Two of these tests are the most important of the whole storage layer: the
 * one proving that the bytes come back exactly as sent — the SDK's defaults
 * silently corrupt them on this storage — and the one proving that the delivery
 * identity cannot read the quarantine at all.
 */
class ObjectStorageTest {

    private static final StorageProperties.Credentials INGEST =
            new StorageProperties.Credentials("praxedo-ingest", "praxedo-ingest-secret");
    private static final StorageProperties.Credentials WORKER =
            new StorageProperties.Credentials("praxedo-worker", "praxedo-worker-secret");
    private static final StorageProperties.Credentials DELIVERY =
            new StorageProperties.Credentials("praxedo-delivery", "praxedo-delivery-secret");

    private S3QuarantineWriter quarantine;
    private S3WorkerStorage worker;
    private S3ServableReader servable;

    @BeforeEach
    void adapters() {
        StorageProperties properties = new StorageProperties(
                URI.create(SeaweedFsContainer.endpoint()), "us-east-1",
                SeaweedFsContainer.QUARANTINE, SeaweedFsContainer.SERVABLE,
                Duration.ofSeconds(2), Duration.ofMinutes(2), INGEST, WORKER, DELIVERY);
        quarantine = new S3QuarantineWriter(S3Clients.forIdentity(properties, INGEST, Retries.BY_CALLER),
                SeaweedFsContainer.QUARANTINE);
        worker = new S3WorkerStorage(S3Clients.forIdentity(properties, WORKER, Retries.BY_CALLER),
                SeaweedFsContainer.QUARANTINE, SeaweedFsContainer.SERVABLE);
        servable = new S3ServableReader(S3Clients.forIdentity(properties, DELIVERY, Retries.BY_SDK),
                SeaweedFsContainer.SERVABLE);
    }

    @Nested
    @DisplayName("integrity")
    class Integrity {

        @Test
        @DisplayName("the stored bytes are exactly the bytes sent — the SDK defaults would corrupt them here")
        void the_bytes_come_back_exactly() {
            ObjectKey key = freshKey();
            long size = 3L * 1024 * 1024 + 17;   // several SDK chunks, and not a round number

            quarantine.write(key, TestContent.generated(size), size);

            try (InputStream stored = worker.openQuarantined(key)) {
                assertThat(TestContent.digestOf(stored)).isEqualTo(TestContent.digestOfGenerated(size));
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        @Test
        void a_promoted_copy_is_byte_for_byte_the_quarantined_one() {
            ObjectKey key = freshKey();
            long size = 1024L * 1024;
            quarantine.write(key, TestContent.generated(size), size);

            worker.writeServable(key, worker.openQuarantined(key), size);

            try (ContentStream served = servable.open(key, ByteRange.WHOLE_OBJECT)) {
                assertThat(served.length()).isEqualTo(size);
                assertThat(served.isPartial()).isFalse();
                assertThat(TestContent.digestOf(served.content())).isEqualTo(TestContent.digestOfGenerated(size));
            }
        }
    }

    @Nested
    @DisplayName("isolation — enforced by the storage, not by the code")
    class Isolation {

        @Test
        @DisplayName("⭐ the delivery identity cannot read the quarantine: 403, whatever the code asks")
        void delivery_cannot_read_the_quarantine() {
            ObjectKey key = freshKey();
            quarantine.write(key, TestContent.generated(64), 64);

            try (S3Client delivery = SeaweedFsContainer.client(DELIVERY.accessKey(), DELIVERY.secretKey())) {
                assertThatExceptionOfType(S3Exception.class)
                        .isThrownBy(() -> delivery.getObject(GetObjectRequest.builder()
                                .bucket(SeaweedFsContainer.QUARANTINE).key(key.value()).build()))
                        .satisfies(refused -> assertThat(refused.statusCode()).isEqualTo(403));
                assertThatExceptionOfType(S3Exception.class)
                        .isThrownBy(() -> delivery.headObject(HeadObjectRequest.builder()
                                .bucket(SeaweedFsContainer.QUARANTINE).key(key.value()).build()))
                        .satisfies(refused -> assertThat(refused.statusCode()).isEqualTo(403));
            }
        }

        @Test
        @DisplayName("the delivery identity cannot write anything, not even to the servable area")
        void delivery_is_read_only() {
            try (S3Client delivery = SeaweedFsContainer.client(DELIVERY.accessKey(), DELIVERY.secretKey())) {
                assertThatExceptionOfType(S3Exception.class)
                        .isThrownBy(() -> delivery.putObject(PutObjectRequest.builder()
                                        .bucket(SeaweedFsContainer.SERVABLE).key(freshKey().value()).build(),
                                RequestBody.fromString("planted")))
                        .satisfies(refused -> assertThat(refused.statusCode()).isEqualTo(403));
            }
        }

        @Test
        @DisplayName("the ingest identity writes to the quarantine but cannot read it back")
        void ingest_is_write_only() {
            ObjectKey key = freshKey();
            quarantine.write(key, TestContent.generated(64), 64);

            try (S3Client ingest = SeaweedFsContainer.client(INGEST.accessKey(), INGEST.secretKey())) {
                assertThatExceptionOfType(S3Exception.class)
                        .isThrownBy(() -> ingest.getObject(GetObjectRequest.builder()
                                .bucket(SeaweedFsContainer.QUARANTINE).key(key.value()).build()))
                        .satisfies(refused -> assertThat(refused.statusCode()).isEqualTo(403));
            }
        }

        @Test
        @DisplayName("the ingest identity can discard what it wrote — how a rejected upload cleans up")
        void ingest_can_discard_its_own_object() {
            ObjectKey key = freshKey();
            quarantine.write(key, TestContent.generated(64), 64);

            quarantine.discard(key);

            assertThatExceptionOfType(ObjectMissingException.class).isThrownBy(() -> worker.openQuarantined(key));
        }
    }

    @Nested
    @DisplayName("ranges")
    class Ranges {

        @Test
        void a_range_returns_exactly_that_slice() {
            ObjectKey key = promoted(1_000);

            try (ContentStream slice = servable.open(key, ByteRange.of(26, 51))) {
                assertThat(slice.isPartial()).isTrue();
                assertThat(slice.firstByte()).isEqualTo(26);
                assertThat(slice.length()).isEqualTo(26);
                assertThat(slice.totalLength()).isEqualTo(1_000);
                assertThat(new String(readAll(slice.content()))).isEqualTo("abcdefghijklmnopqrstuvwxyz");
            }
        }

        @Test
        void an_open_ended_range_runs_to_the_end() {
            ObjectKey key = promoted(1_000);

            try (ContentStream tail = servable.open(key, ByteRange.from(990))) {
                assertThat(tail.length()).isEqualTo(10);
                assertThat(tail.lastByte()).isEqualTo(999);
            }
        }

        @Test
        void a_range_past_the_end_is_refused_with_the_real_size() {
            ObjectKey key = promoted(1_000);

            assertThatExceptionOfType(RangeNotSatisfiableException.class)
                    .isThrownBy(() -> servable.open(key, ByteRange.from(1_000)))
                    .satisfies(refused -> assertThat(refused.totalLength()).isEqualTo(1_000));
        }
    }

    @Nested
    @DisplayName("absence and housekeeping")
    class Housekeeping {

        @Test
        void a_missing_object_is_reported_as_such() {
            assertThatExceptionOfType(ObjectMissingException.class)
                    .isThrownBy(() -> servable.open(freshKey(), ByteRange.WHOLE_OBJECT));
            assertThatExceptionOfType(ObjectMissingException.class)
                    .isThrownBy(() -> worker.openQuarantined(freshKey()));
        }

        @Test
        @DisplayName("deleting what is already gone is a success")
        void deletes_are_idempotent() {
            ObjectKey absent = freshKey();

            assertThatNoException().isThrownBy(() -> worker.deleteQuarantined(absent));
            assertThatNoException().isThrownBy(() -> worker.deleteServable(absent));
            assertThatNoException().isThrownBy(() -> quarantine.discard(absent));
        }

        @Test
        void the_sweep_listing_only_returns_objects_older_than_asked() {
            ObjectKey key = freshKey();
            quarantine.write(key, TestContent.generated(8), 8);

            List<ObjectKey> anHourAgo = worker.listQuarantinedBefore(Instant.now().minus(Duration.ofHours(1)), null, 10_000);
            List<ObjectKey> inAMinute = worker.listQuarantinedBefore(Instant.now().plus(Duration.ofMinutes(1)), null, 10_000);

            assertThat(anHourAgo).doesNotContain(key);
            assertThat(inAMinute).contains(key);
        }

        @Test
        @DisplayName("the sweep listing resumes after a key, in key order: retained objects cannot hide the rest")
        void the_sweep_listing_resumes_after_a_key() {
            List<ObjectKey> written = IntStream.range(0, 3).mapToObj(i -> freshKey()).sorted(
                    Comparator.comparing(ObjectKey::value)).toList();
            written.forEach(key -> quarantine.write(key, TestContent.generated(8), 8));
            Instant inAMinute = Instant.now().plus(Duration.ofMinutes(1));

            List<ObjectKey> afterFirst = worker.listQuarantinedBefore(inAMinute, written.get(0), 10_000);

            assertThat(afterFirst).doesNotContain(written.get(0)).contains(written.get(1), written.get(2));
            assertThat(afterFirst).isSortedAccordingTo(Comparator.comparing(ObjectKey::value));
        }
    }

    private ObjectKey promoted(long size) {
        ObjectKey key = freshKey();
        quarantine.write(key, TestContent.generated(size), size);
        worker.writeServable(key, worker.openQuarantined(key), size);
        return key;
    }

    private static ObjectKey freshKey() {
        return ObjectKey.of(FileId.random());
    }

    private static byte[] readAll(InputStream content) {
        try {
            return content.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
