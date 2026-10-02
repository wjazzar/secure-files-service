package com.praxedo.securefiles.domain.file.model;

/**
 * The two storage areas, held by two different sets of credentials.
 *
 * <p>This enum is the domain's view of a property enforced elsewhere: the
 * delivery role has no read access whatsoever to {@link #QUARANTINE}. Even
 * with a corrupted status in the database, the download path cannot reach an
 * unscanned object — the storage refuses.
 */
public enum StorageArea {

    /** Where uploads land. Never readable by the delivery role. */
    QUARANTINE,

    /** Where cleared content lives. Read-only for the delivery role. */
    SERVABLE
}
