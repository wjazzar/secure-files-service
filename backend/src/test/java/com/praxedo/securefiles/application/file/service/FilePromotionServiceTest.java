package com.praxedo.securefiles.application.file.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.model.WorkerSettings;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.domain.file.valueobject.Sha256;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.testsupport.InMemoryWorkQueue;
import com.praxedo.securefiles.testsupport.InMemoryWorkerStorage;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.Leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Promotion, interrupted at every point where it can be.
 *
 * <p>Each test stops the protocol at one step and checks two things: the file
 * never became available without a verified copy, and the state it was left in
 * is one the service recovers from on its own.
 */
class FilePromotionServiceTest {

    private static final Duration PROMOTION_LEASE = Duration.ofMinutes(15);
    private static final byte[] CONTENT = "the analysed bytes, nothing else".getBytes(StandardCharsets.UTF_8);

    private InMemoryWorkQueue queue;
    private InMemoryWorkerStorage storage;
    private FilePromotionService promotion;
    private StoredFile promoting;
    private LeaseToken claim;

    @BeforeEach
    void aFileWithACleanVerdict() {
        queue = new InMemoryWorkQueue();
        storage = new InMemoryWorkerStorage();
        WorkerSettings settings = new WorkerSettings(Leases.fixed(Duration.ofMinutes(10)), Leases.fixed(PROMOTION_LEASE),
                5, Duration.ofSeconds(1), Duration.ofMinutes(1));
        promotion = new FilePromotionService(queue, storage, settings, Clock.systemUTC());

        Sha256 digest = new Sha256(TestContent.digestOf(new ByteArrayInputStream(CONTENT)));
        StoredFile file = StoredFile.received(FileId.random(), new OwnerId("alice"), FileName.sanitised("doc.pdf"),
                ContentType.OCTET_STREAM, CONTENT.length, digest, Instant.now());
        queue.add(file);
        storage.quarantine.put(file.objectKey().value(), CONTENT);

        claim = LeaseToken.random();
        StoredFile claimed = queue.claimNextDue("worker", claim, Leases.fixed(Duration.ofMinutes(10)), 5).orElseThrow();
        StoredFile concluded = claimed.scanned(new ScanVerdict(ScanResult.CLEAN, null, "ClamAV", "1.4.6", "28098",
                digest, Instant.now(), Duration.ofMillis(5)), Instant.now());
        queue.writeVerdict(concluded, claim, Duration.ofMinutes(15));
        promoting = queue.current(file.id());
    }

    @Test
    @DisplayName("nominal: available, the servable copy is the analysed bytes, the source is gone")
    void a_clean_file_is_promoted() {
        StoredFile result = promotion.promote(promoting, claim, PROMOTION_LEASE);

        assertThat(result.status()).isEqualTo(FileStatus.AVAILABLE);
        assertThat(queue.current(promoting.id()).isDownloadable()).isTrue();
        assertThat(storage.servable.get(promoting.objectKey().value())).isEqualTo(CONTENT);
        assertThat(storage.quarantine).isEmpty();
    }

    @Test
    @DisplayName("① interrupted before the copy: back to the queue, nothing servable")
    void the_source_cannot_be_read() {
        storage.failOpen = true;

        promotion.promote(promoting, claim, PROMOTION_LEASE);

        assertNotAvailableAndRequeued();
        assertThat(storage.servable).isEmpty();
    }

    @Test
    @DisplayName("② interrupted during the copy: back to the queue, nothing servable")
    void the_copy_fails_midway() {
        storage.failWrite = true;

        promotion.promote(promoting, claim, PROMOTION_LEASE);

        assertNotAvailableAndRequeued();
        assertThat(storage.servable).isEmpty();
    }

    @Test
    @DisplayName("③ the copy does not match the attestation: removed, refused, back to the queue")
    void the_copy_does_not_match() {
        storage.corruptFromRead = 1;

        promotion.promote(promoting, claim, PROMOTION_LEASE);

        assertNotAvailableAndRequeued();
        assertThat(storage.servable).as("the unverified copy is removed").isEmpty();
        assertThat(queue.failureReasons()).anySatisfy(reason -> assertThat(reason).contains("does not match"));
    }

    @Test
    @DisplayName("④ the lease is lost after the copy: no compare-and-set, the source stays for the new owner")
    void the_lease_is_lost_before_the_commit() {
        storage.afterServableWrite = () -> queue.stealLease(promoting.id());

        promotion.promote(promoting, claim, PROMOTION_LEASE);

        StoredFile after = queue.current(promoting.id());
        assertThat(after.status()).isEqualTo(FileStatus.PROMOTING);
        assertThat(after.isDownloadable()).isFalse();
        assertThat(storage.quarantine).as("the new owner still needs the source").isNotEmpty();
    }

    @Test
    @DisplayName("⑤ the source cannot be removed after the commit: available anyway, the sweep finishes the job")
    void the_source_cannot_be_removed() {
        storage.failDeleteQuarantined = true;

        StoredFile result = promotion.promote(promoting, claim, PROMOTION_LEASE);

        assertThat(result.status()).isEqualTo(FileStatus.AVAILABLE);
        assertThat(queue.current(promoting.id()).isDownloadable()).isTrue();
        assertThat(storage.quarantine).as("left for the quarantine sweep").isNotEmpty();
    }

    @Test
    void only_a_file_with_a_clean_verdict_can_be_promoted() {
        StoredFile available = promotion.promote(promoting, claim, PROMOTION_LEASE);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> promotion.promote(available, claim, PROMOTION_LEASE));
    }

    private void assertNotAvailableAndRequeued() {
        StoredFile after = queue.current(promoting.id());
        assertThat(after.isDownloadable()).isFalse();
        assertThat(after.status()).isEqualTo(FileStatus.RETRY_WAIT);
        assertThat(after.currentLease()).isEmpty();
    }
}
