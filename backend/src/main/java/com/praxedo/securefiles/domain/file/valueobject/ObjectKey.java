package com.praxedo.securefiles.domain.file.valueobject;

import java.util.Objects;

/**
 * Key of an object in the storage areas.
 *
 * <p>Always derived from the generated {@link FileId}, never from the name the
 * caller supplied (rule B-3). Building it here rather than in the storage
 * adapter keeps that rule in one reviewable place.
 */
public record ObjectKey(String value) {

    public ObjectKey {
        Objects.requireNonNull(value, "object key");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An object key cannot be blank");
        }
    }

    /** The canonical key of a file, in either area: the file id. */
    public static ObjectKey of(FileId fileId) {
        return new ObjectKey(fileId.value().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
