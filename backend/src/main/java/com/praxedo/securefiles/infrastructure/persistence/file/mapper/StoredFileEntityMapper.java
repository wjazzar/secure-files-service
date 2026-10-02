package com.praxedo.securefiles.infrastructure.persistence.file.mapper;

import java.time.Duration;

import com.praxedo.securefiles.domain.file.model.Lease;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.domain.file.valueobject.Sha256;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.infrastructure.persistence.file.entity.StoredFileEntity;

/**
 * Converts between the aggregate and its JPA row — the only place that knows
 * both.
 *
 * <p>Towards the domain, every entity goes through {@link StoredFile}'s
 * constructor: the invariants are re-checked <em>on read</em>, exactly as
 * {@link StoredFileRowMapper} does for the rows the work queue returns. The two
 * read paths cannot disagree on what a valid file is, because neither decides
 * it.
 */
public final class StoredFileEntityMapper {

    private StoredFileEntityMapper() {
    }

    public static StoredFileEntity toEntity(StoredFile file) {
        ScanVerdict verdict = file.scan().orElse(null);
        Lease lease = file.currentLease().orElse(null);
        return new StoredFileEntity(
                file.id().value(),
                file.owner().value(),
                file.filename().value(),
                file.contentType().value(),
                file.sizeBytes(),
                file.sha256().value(),
                file.area(),
                file.objectKey().value(),
                file.status(),
                file.reason().orElse(null),
                verdict == null ? null : verdict.result(),
                verdict == null ? null : verdict.threatName(),
                verdict == null ? null : verdict.engine(),
                verdict == null ? null : verdict.engineVersion(),
                verdict == null ? null : verdict.signatureVersion(),
                verdict == null ? null : verdict.scannedContent().value(),
                verdict == null ? null : verdict.scannedAt(),
                verdict == null ? null : Math.toIntExact(verdict.duration().toMillis()),
                file.attempts(),
                lease == null ? null : lease.token().value(),
                lease == null ? null : lease.holder(),
                lease == null ? null : lease.expiresAt(),
                file.uploadedAt(),
                file.statusChangedAt(),
                file.version());
    }

    public static StoredFile toDomain(StoredFileEntity row) {
        return new StoredFile(
                new FileId(row.id()),
                new OwnerId(row.ownerId()),
                new FileName(row.originalFilename()),
                new ContentType(row.detectedContentType()),
                row.sizeBytes(),
                new Sha256(row.contentSha256()),
                row.storageArea(),
                row.status(),
                row.statusReason(),
                verdict(row),
                row.attempts(),
                lease(row),
                row.uploadedAt(),
                row.statusChangedAt(),
                row.version());
    }

    private static ScanVerdict verdict(StoredFileEntity row) {
        if (row.scanResult() == null) {
            return null;
        }
        return new ScanVerdict(
                row.scanResult(),
                row.scanThreatName(),
                row.scanEngine(),
                row.scanEngineVersion(),
                row.scanSignatureVersion(),
                new Sha256(row.scannedSha256()),
                row.scannedAt(),
                Duration.ofMillis(row.scanDurationMs() == null ? 0 : row.scanDurationMs()));
    }

    private static Lease lease(StoredFileEntity row) {
        if (row.leaseToken() == null) {
            return null;
        }
        return new Lease(new LeaseToken(row.leaseToken()), row.leaseHolder(), row.leaseExpiresAt());
    }
}
