package com.praxedo.securefiles.application.file.port.in;

import java.util.Optional;
import java.util.Set;

import com.praxedo.securefiles.application.file.model.FileCounters;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Consult one's files: a page, one file, the counters. Every read is scoped to
 * one owner.
 *
 * <p>Called by the web adapter for {@code GET /api/v1/files},
 * {@code GET /api/v1/files/{id}} and {@code GET /api/v1/files/summary}.
 */
public interface QueryFilesUseCase {

    /**
     * @param statuses public statuses to keep; empty means all of them
     * @param search   "contains" search on the file name; blank means none
     */
    PageResult<StoredFile> list(OwnerId owner, Set<PublicStatus> statuses, String search, PageQuery page);

    /** Empty for an unknown file — and for someone else's, which must look the same. */
    Optional<StoredFile> find(FileId id, OwnerId owner);

    /** The counters, honouring the same search as the listing. */
    FileCounters count(OwnerId owner, String search);
}
