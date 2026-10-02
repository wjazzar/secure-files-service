package com.praxedo.securefiles.domain.owner.valueobject;

import java.util.Objects;

/**
 * Owner of a file space: the subject the identity provider vouched for (the
 * {@code sub} claim). Every file has one, and no value stands for "nobody in
 * particular".
 */
public record OwnerId(String value) {

    public OwnerId {
        Objects.requireNonNull(value, "owner id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An owner id cannot be blank");
        }
        if (value.length() > 255) {
            throw new IllegalArgumentException("An owner id cannot exceed 255 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
