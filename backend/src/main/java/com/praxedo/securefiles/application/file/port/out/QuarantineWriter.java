package com.praxedo.securefiles.application.file.port.out;

import java.io.InputStream;

import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

/**
 * What the upload path may do with the object storage — and nothing more.
 *
 * <p>Implemented with the {@code ingest} credentials, which can write to the
 * quarantine and delete from it, but cannot <em>read</em> it and cannot even
 * list it (measured, not assumed: see {@code docs/prompts/B-005}). The upload
 * path therefore has no way to serve anything, even by mistake.
 *
 * <p>The signatures forbid the other mistake too: content goes in as a stream
 * of known length, never as a {@code byte[]} (rule B-1).
 */
public interface QuarantineWriter {

    /**
     * Streams {@code sizeBytes} bytes into the quarantine under {@code key}.
     *
     * @throws StorageUnavailableException when the storage cannot be reached
     */
    void write(ObjectKey key, InputStream content, long sizeBytes);

    /**
     * Removes an object the upload itself wrote and then rejected. Absence is
     * a success: the caller only needs the object to be gone.
     */
    void discard(ObjectKey key);
}
