package com.praxedo.securefiles.application.file.port.out;

import java.util.Map;
import java.util.Optional;

import com.praxedo.securefiles.application.file.model.FileQuery;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * What the API reads and writes: the catalogue of files.
 *
 * <p>Everything here is ordinary data access — by identifier, by owner, in a
 * sorted page, searched and counted. It is implemented with JPA, because that
 * is where Spring Data removes the most code for the least risk.
 *
 * <p>The transitions of the state machine are deliberately <em>not</em> here:
 * they carry concurrency guarantees that no ORM expresses, and they live in
 * {@link FileWorkQueue}.
 *
 * <p>Note what the reading methods take: a {@link FileQuery}, which cannot be
 * built without an owner. Scoping is not a parameter an implementation may
 * forget — it is the only way to ask a question.
 */
public interface FileCatalog {

    /**
     * Records a freshly received file and, in the same row, the work item for
     * its analysis — so there is never a moment where a file exists without
     * being queued.
     *
     * @throws DuplicateObjectKeyException if the storage key is already taken
     */
    StoredFile insert(StoredFile file);

    Optional<StoredFile> findById(FileId id);

    /** Used by every read path: someone else's file must look unknown. */
    Optional<StoredFile> findOwnedBy(FileId id, OwnerId owner);

    /** One page of the matching files, ordered as the query asks. */
    PageResult<StoredFile> findPage(FileQuery query, PageQuery page);

    /**
     * How many matching files sit in each internal state.
     *
     * <p>States with no file are simply absent: filling the gaps is the
     * application's job, because it is the <em>published</em> statuses that
     * must all be present, not the internal ones.
     */
    Map<FileStatus, Long> countByStatus(FileQuery query);

    /**
     * How many files are <em>pending</em>: not yet terminal — waiting, being
     * analysed, waiting for a retry, or being promoted — all owners together.
     *
     * <p>"Pending" has this one meaning in the service's code, its settings
     * ({@code max-pending-files}), its metrics and its error code
     * ({@code TOO_MANY_PENDING_FILES}). It is broader than the public status
     * {@code PENDING}, which only covers the files still waiting.
     *
     * <p>It is the one bounded resource of the service: a queue that grows
     * without limit ends up filling the quarantine. Admission control refuses
     * new uploads above a threshold rather than accepting them and collapsing.
     */
    long countPendingFiles();

    /**
     * The state of the files stored under these keys; keys with no file are
     * absent from the answer. Used by the orphan sweep, which must tell an
     * object nobody references from one still waiting for its analysis.
     */
    Map<String, FileStatus> statusesByObjectKey(java.util.Collection<String> objectKeys);

    /** Thrown when two uploads somehow produced the same storage key. */
    class DuplicateObjectKeyException extends RuntimeException {
        public DuplicateObjectKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
