package com.praxedo.securefiles.infrastructure.persistence.file.adapter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.application.file.port.out.DownloadAudit;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Download events, appended to the same journal the state transitions are
 * written to by trigger ({@code V6__audit.sql}).
 */
@Repository
public class JdbcDownloadAudit implements DownloadAudit {

    private final JdbcClient jdbc;

    public JdbcDownloadAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void served(StoredFile file, OwnerId owner, ByteRange range) {
        jdbc.sql("""
                INSERT INTO file_audit_event (file_id, owner_id, event_type, from_status, to_status, actor, details)
                VALUES (:fileId, :ownerId, 'DOWNLOAD_SERVED', CAST(:status AS file_status), CAST(:status AS file_status),
                        :actor, jsonb_build_object('range', CAST(:range AS text)))
                """)
                .param("fileId", file.id().value())
                .param("ownerId", file.owner().value())
                .param("status", file.status().name())
                .param("actor", owner.value())
                .param("range", range.isWholeObject() ? "whole" : range.asHttpHeader())
                .update();
    }
}
