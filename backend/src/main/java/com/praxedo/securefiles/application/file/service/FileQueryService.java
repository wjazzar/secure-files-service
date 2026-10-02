package com.praxedo.securefiles.application.file.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.praxedo.securefiles.application.file.model.FileCounters;
import com.praxedo.securefiles.application.file.model.FileQuery;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.application.file.port.in.QueryFilesUseCase;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * The three reads the API offers: one page, one file, the counters.
 *
 * <p>It is thin on purpose. What it does own is the part that must not be
 * duplicated in a controller: scoping every read to one owner, translating
 * public statuses into internal states, and folding counts onto the six
 * published statuses. All three are testable without starting anything.
 */
public class FileQueryService implements QueryFilesUseCase {

    private final FileCatalog catalog;

    public FileQueryService(FileCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * One page of the owner's files.
     *
     * @param statuses public statuses to keep; empty means all of them
     * @param search   "contains" search on the file name; blank means none
     */
    @Override
    public PageResult<StoredFile> list(OwnerId owner, Set<PublicStatus> statuses, String search, PageQuery page) {
        return catalog.findPage(FileQuery.of(owner, statuses, search), page);
    }

    /**
     * One file — or nothing, which is also the answer for a file belonging to
     * someone else. The API must not reveal that it exists.
     */
    @Override
    public Optional<StoredFile> find(FileId id, OwnerId owner) {
        return catalog.findOwnedBy(id, owner);
    }

    /**
     * The counters, honouring the same search as the listing so that what the
     * filters announce matches what the table shows.
     */
    @Override
    public FileCounters count(OwnerId owner, String search) {
        Map<FileStatus, Long> internal = catalog.countByStatus(FileQuery.of(owner, Set.of(), search));

        Map<PublicStatus, Long> published = new EnumMap<>(PublicStatus.class);
        internal.forEach((state, count) ->
                published.merge(state.publicStatus(), count, Long::sum));

        return FileCounters.of(published);
    }
}
