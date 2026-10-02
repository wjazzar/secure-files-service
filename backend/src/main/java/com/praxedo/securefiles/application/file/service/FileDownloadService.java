package com.praxedo.securefiles.application.file.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.praxedo.securefiles.application.file.exception.DownloadRefusedException;
import com.praxedo.securefiles.application.file.exception.ObjectMissingException;
import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.application.file.model.ContentStream;
import com.praxedo.securefiles.application.file.model.FileDownload;
import com.praxedo.securefiles.application.file.port.in.DownloadFileUseCase;
import com.praxedo.securefiles.application.file.port.out.DownloadAudit;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.ServableReader;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Serves cleared content — and nothing else, by three independent means.
 *
 * <ol>
 *   <li><strong>The state is re-read at the moment of serving.</strong> No
 *       earlier answer is trusted: each download reads the row and asks the
 *       only question the download path is allowed to ask,
 *       {@link StoredFile#isDownloadable()}.</li>
 *   <li><strong>The database refuses an available row without a clean
 *       attestation of its own digest</strong>, through total {@code CHECK}
 *       predicates — so the answer to that question cannot be forged by a bad
 *       write.</li>
 *   <li><strong>The storage refuses the quarantine to this component.</strong>
 *       Content is opened through {@link ServableReader}, whose credentials have
 *       no right on the quarantine at all.</li>
 * </ol>
 *
 * <p>The caller's identity is the only credential (ADR-0013): the session a
 * browser signed in with, or the bearer token of a third-party system. There
 * is no second one — no signed link — to issue, keep secret or rotate.
 */
public class FileDownloadService implements DownloadFileUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(FileDownloadService.class);

    private final FileCatalog catalog;
    private final ServableReader servable;
    private final DownloadAudit audit;

    public FileDownloadService(FileCatalog catalog, ServableReader servable, DownloadAudit audit) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.servable = Objects.requireNonNull(servable, "servable reader");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Opens the content, then records the download — before a single byte is sent. */
    @Override
    public FileDownload open(FileId id, OwnerId owner, ByteRange range) {
        StoredFile file = servableFile(id, owner);
        ContentStream content;
        try {
            content = servable.open(file.objectKey(), range);
        } catch (ObjectMissingException missing) {
            LOG.error("INVARIANT ANOMALY: file {} is AVAILABLE but has no servable content", file.id());
            throw new DownloadRefusedException.ContentMissing();
        }
        try {
            audit.served(file, owner, range);
        } catch (RuntimeException unrecorded) {
            content.close();
            throw unrecorded;
        }
        return new FileDownload(file, content);
    }

    private StoredFile servableFile(FileId id, OwnerId owner) {
        StoredFile file = catalog.findOwnedBy(id, owner).orElseThrow(DownloadRefusedException.UnknownFile::new);
        if (!file.isDownloadable()) {
            throw new DownloadRefusedException.NotServable(file.publicStatus());
        }
        return file;
    }
}
