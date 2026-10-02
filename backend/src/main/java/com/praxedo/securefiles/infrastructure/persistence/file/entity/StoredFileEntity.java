package com.praxedo.securefiles.infrastructure.persistence.file.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.domain.file.model.StatusReason;
import com.praxedo.securefiles.domain.file.model.StorageArea;
import com.praxedo.securefiles.infrastructure.persistence.file.mapper.StatusReasonConverter;
import com.praxedo.securefiles.infrastructure.persistence.file.mapper.StoredFileEntityMapper;

/**
 * One row of {@code stored_file}, as JPA reads and inserts it.
 *
 * <p><strong>A persistence model, not the aggregate.</strong> The state machine
 * and its invariant live in the domain's {@code StoredFile}, which knows nothing
 * of JPA; {@link StoredFileEntityMapper} converts between the two, and every
 * conversion towards the domain goes through the aggregate's constructor, so
 * a row read here is checked like any other file.
 *
 * <p>The mapping stays deliberately boring: one field per column, no setter, no
 * behaviour. Nothing updates an entity: JPA inserts a new file and reads the
 * catalogue, and every state transition is a conditional statement of the work
 * queue. An entity is therefore never dirty, and Hibernate never writes a
 * status on its own.
 */
@Entity
@Table(name = "stored_file")
public class StoredFileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "detected_content_type", nullable = false)
    private String detectedContentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_sha256", nullable = false)
    private String contentSha256;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "storage_area", nullable = false)
    private StorageArea storageArea;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private FileStatus status;

    @Convert(converter = StatusReasonConverter.class)
    @Column(name = "status_reason")
    private StatusReason statusReason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "scan_result")
    private ScanResult scanResult;

    @Column(name = "scan_threat_name")
    private String scanThreatName;

    @Column(name = "scan_engine")
    private String scanEngine;

    @Column(name = "scan_engine_version")
    private String scanEngineVersion;

    @Column(name = "scan_signature_version")
    private String scanSignatureVersion;

    @Column(name = "scanned_sha256")
    private String scannedSha256;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "scan_duration_ms")
    private Integer scanDurationMs;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_holder")
    private String leaseHolder;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For Hibernate only. */
    protected StoredFileEntity() {
    }

    /** Every column, in the table's order — called by the mapper only. */
    public StoredFileEntity(UUID id, String ownerId, String originalFilename, String detectedContentType,
                            long sizeBytes, String contentSha256, StorageArea storageArea, String objectKey,
                            FileStatus status, StatusReason statusReason, ScanResult scanResult,
                            String scanThreatName, String scanEngine, String scanEngineVersion,
                            String scanSignatureVersion, String scannedSha256, Instant scannedAt,
                            Integer scanDurationMs, int attempts, UUID leaseToken, String leaseHolder,
                            Instant leaseExpiresAt, Instant uploadedAt, Instant statusChangedAt, long version) {
        this.id = id;
        this.ownerId = ownerId;
        this.originalFilename = originalFilename;
        this.detectedContentType = detectedContentType;
        this.sizeBytes = sizeBytes;
        this.contentSha256 = contentSha256;
        this.storageArea = storageArea;
        this.objectKey = objectKey;
        this.status = status;
        this.statusReason = statusReason;
        this.scanResult = scanResult;
        this.scanThreatName = scanThreatName;
        this.scanEngine = scanEngine;
        this.scanEngineVersion = scanEngineVersion;
        this.scanSignatureVersion = scanSignatureVersion;
        this.scannedSha256 = scannedSha256;
        this.scannedAt = scannedAt;
        this.scanDurationMs = scanDurationMs;
        this.attempts = attempts;
        this.leaseToken = leaseToken;
        this.leaseHolder = leaseHolder;
        this.leaseExpiresAt = leaseExpiresAt;
        this.uploadedAt = uploadedAt;
        this.statusChangedAt = statusChangedAt;
        this.version = version;
    }

    public UUID id() {
        return id;
    }

    public String ownerId() {
        return ownerId;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public String detectedContentType() {
        return detectedContentType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String contentSha256() {
        return contentSha256;
    }

    public StorageArea storageArea() {
        return storageArea;
    }

    public FileStatus status() {
        return status;
    }

    public StatusReason statusReason() {
        return statusReason;
    }

    public ScanResult scanResult() {
        return scanResult;
    }

    public String scanThreatName() {
        return scanThreatName;
    }

    public String scanEngine() {
        return scanEngine;
    }

    public String scanEngineVersion() {
        return scanEngineVersion;
    }

    public String scanSignatureVersion() {
        return scanSignatureVersion;
    }

    public String scannedSha256() {
        return scannedSha256;
    }

    public Instant scannedAt() {
        return scannedAt;
    }

    public Integer scanDurationMs() {
        return scanDurationMs;
    }

    public int attempts() {
        return attempts;
    }

    public UUID leaseToken() {
        return leaseToken;
    }

    public String leaseHolder() {
        return leaseHolder;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "StoredFileEntity[" + id + ", " + status + "]";
    }
}
