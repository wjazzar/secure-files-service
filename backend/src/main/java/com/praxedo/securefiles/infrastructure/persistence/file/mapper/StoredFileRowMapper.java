package com.praxedo.securefiles.infrastructure.persistence.file.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;

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

/**
 * Rebuilds an aggregate from a row.
 *
 * <p>Note what this costs and buys: every row goes through {@link StoredFile}'s
 * canonical constructor, so the invariants are re-checked <em>on read</em>, not
 * only on write. A row that a manual {@code UPDATE} left inconsistent does not
 * quietly flow through the service — it fails loudly, here, before anything is
 * served.
 */
public class StoredFileRowMapper implements RowMapper<StoredFile> {

    public static final StoredFileRowMapper INSTANCE = new StoredFileRowMapper();

    @Override
    public StoredFile mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StoredFile(
                new FileId(rs.getObject("id", UUID.class)),
                new OwnerId(rs.getString("owner_id")),
                new FileName(rs.getString("original_filename")),
                ContentType.of(rs.getString("detected_content_type")),
                rs.getLong("size_bytes"),
                Sha256.of(rs.getString("content_sha256").trim()),
                StorageArea.valueOf(rs.getString("storage_area")),
                FileStatus.valueOf(rs.getString("status")),
                StatusReasonConverter.fromColumn(rs.getString("status_reason")),
                verdict(rs),
                rs.getInt("attempts"),
                lease(rs),
                instant(rs, "uploaded_at"),
                instant(rs, "status_changed_at"),
                rs.getLong("version"));
    }

    private static ScanVerdict verdict(ResultSet rs) throws SQLException {
        String result = rs.getString("scan_result");
        if (result == null) {
            return null;
        }
        String scannedSha = rs.getString("scanned_sha256");
        return new ScanVerdict(
                ScanResult.valueOf(result),
                rs.getString("scan_threat_name"),
                rs.getString("scan_engine"),
                rs.getString("scan_engine_version"),
                rs.getString("scan_signature_version"),
                scannedSha == null ? null : Sha256.of(scannedSha.trim()),
                instant(rs, "scanned_at"),
                Duration.ofMillis(rs.getInt("scan_duration_ms")));
    }

    private static Lease lease(ResultSet rs) throws SQLException {
        UUID token = rs.getObject("lease_token", UUID.class);
        if (token == null) {
            return null;
        }
        return new Lease(new LeaseToken(token), rs.getString("lease_holder"), instant(rs, "lease_expires_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
