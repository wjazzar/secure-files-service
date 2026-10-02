package com.praxedo.securefiles.infrastructure.persistence.file.mapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.praxedo.securefiles.domain.file.model.FileStatus;
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
import com.praxedo.securefiles.infrastructure.persistence.file.entity.StoredFileEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregate and its JPA row must describe the same file, in every state —
 * and a row the domain would refuse must be refused on read.
 */
class StoredFileEntityMapperTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final Sha256 CONTENT = Sha256.of("c".repeat(64));

    static Stream<StoredFile> everyShapeOfFile() {
        StoredFile received = StoredFile.received(FileId.random(), new OwnerId("alice"),
                FileName.sanitised("rapport.pdf"), ContentType.of("application/pdf"), 2_048L, CONTENT, T0);
        StoredFile scanning = received.claimedBy(LeaseToken.random(), "worker-1", T0.plusSeconds(1), LEASE);
        StoredFile promoting = scanning.scanned(verdict(ScanResult.CLEAN, null), T0.plusSeconds(2));
        return Stream.of(
                received,
                scanning,
                promoting,
                promoting.promoted(T0.plusSeconds(3)),
                scanning.scanned(verdict(ScanResult.INFECTED, "Eicar-Test-Signature"), T0.plusSeconds(2)),
                scanning.scanned(verdict(ScanResult.UNSCANNABLE, "Heuristics.Encrypted.Zip"), T0.plusSeconds(2)),
                scanning.technicalFailure(3, T0.plusSeconds(2)),
                scanning.technicalFailure(1, T0.plusSeconds(2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyShapeOfFile")
    @DisplayName("a file survives the round trip through its row unchanged")
    void round_trip(StoredFile file) {
        StoredFile readBack = StoredFileEntityMapper.toDomain(StoredFileEntityMapper.toEntity(file));

        assertThat(readBack).usingRecursiveComparison().isEqualTo(file);
    }

    @Test
    @DisplayName("the row carries the object key derived from the identifier, never another")
    void object_key_is_derived() {
        StoredFile file = everyShapeOfFile().findFirst().orElseThrow();

        // Read as JPA reads it: the entity has no accessor for a column only queries use.
        assertThat(StoredFileEntityMapper.toEntity(file)).extracting("objectKey").isEqualTo(file.id().value().toString());
    }

    @Test
    @DisplayName("an AVAILABLE row without a clean attestation is refused on read, by the aggregate's constructor")
    void an_inconsistent_row_fails_loudly() {
        StoredFileEntity forged = new StoredFileEntity(UUID.randomUUID(), "alice", "rapport.pdf",
                "application/pdf", 2_048L, CONTENT.value(), StorageArea.SERVABLE, "key",
                FileStatus.AVAILABLE, null, null, null, null, null, null, null, null, null,
                1, null, null, null, T0, T0, 3L);

        assertThatThrownBy(() -> StoredFileEntityMapper.toDomain(forged))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clean verdict");
    }

    private static ScanVerdict verdict(ScanResult result, String detail) {
        return new ScanVerdict(result, detail, "ClamAV", "1.4.6", "28098", CONTENT, T0.plusSeconds(2),
                Duration.ofMillis(120));
    }
}
