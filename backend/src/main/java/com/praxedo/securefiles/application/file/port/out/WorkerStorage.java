package com.praxedo.securefiles.application.file.port.out;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

/**
 * What the worker may do with the object storage: read what it must analyse,
 * write what it has verified, and clean up behind itself.
 *
 * <p>The only component holding credentials on <em>both</em> areas — which is
 * exactly why promotion re-reads the content and checks its digest before the
 * file becomes servable, instead of trusting a server-side copy.
 */
public interface WorkerStorage {

    /** @throws ObjectMissingException when the quarantined object is gone */
    InputStream openQuarantined(ObjectKey key);

    /** Streams verified content into the servable area. */
    void writeServable(ObjectKey key, InputStream content, long sizeBytes);

    /** Absence is a success. */
    void deleteQuarantined(ObjectKey key);

    /** Absence is a success. */
    void deleteServable(ObjectKey key);

    /**
     * Quarantined objects last modified before {@code olderThan}, for the
     * orphan sweep, in key order. Bounded by {@code limit}: a sweep is a
     * background chore, not a batch job that can hold a node for minutes.
     *
     * @param after where the previous sweep stopped: only keys after it are
     *              listed; {@code null} starts from the first key
     */
    List<ObjectKey> listQuarantinedBefore(Instant olderThan, ObjectKey after, int limit);
}
