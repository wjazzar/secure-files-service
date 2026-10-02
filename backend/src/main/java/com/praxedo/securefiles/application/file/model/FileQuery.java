package com.praxedo.securefiles.application.file.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * The parameters of a read: which files it may see, and which of them the
 * caller asked for. It executes nothing and builds no SQL — the catalogue does.
 *
 * <p>Named after the Command/Query split the application layer follows: an
 * {@link UploadCommand} asks for a change, a {@code FileQuery} for a read (with
 * {@link PageQuery} for the page), and {@code QueryFilesUseCase} answers it.
 *
 * <p><strong>The owner is not optional.</strong> Every read goes through this
 * type, and this type cannot be built without an owner — so no query path can
 * forget to scope itself. The owner is the subject the identity provider
 * vouched for.
 *
 * <p>The public statuses a caller asks for are translated here, once, into the
 * internal states they stand for. The persistence adapter therefore never sees
 * a public status, and the projection stays in the domain, where it is tested.
 */
public record FileQuery(OwnerId owner, Set<FileStatus> statuses, String nameContains) {

    public FileQuery {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(statuses, "statuses");
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("A query accepting no status at all would match nothing");
        }
        statuses = Collections.unmodifiableSet(EnumSet.copyOf(statuses));
    }

    /**
     * Builds the query behind a listing or a set of counters.
     *
     * @param requestedPublicStatuses the public statuses the client asked for;
     *                                empty means "no filter", which is every
     *                                internal state rather than none
     * @param search                  the "contains" search on the file name;
     *                                blank means none
     */
    public static FileQuery of(OwnerId owner, Set<PublicStatus> requestedPublicStatuses, String search) {
        Objects.requireNonNull(requestedPublicStatuses, "requested public statuses");
        Set<FileStatus> internalStates = EnumSet.noneOf(FileStatus.class);
        if (requestedPublicStatuses.isEmpty()) {
            internalStates.addAll(EnumSet.allOf(FileStatus.class));
        } else {
            requestedPublicStatuses.forEach(requested -> internalStates.addAll(requested.internalStates()));
        }
        return new FileQuery(owner, internalStates, normalise(search));
    }

    /** No search at all and a search made of spaces are the same request. */
    private static String normalise(String search) {
        return search == null || search.isBlank() ? null : search.strip();
    }
}
