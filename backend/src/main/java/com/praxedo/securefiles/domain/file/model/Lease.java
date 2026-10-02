package com.praxedo.securefiles.domain.file.model;

import java.time.Instant;
import java.util.Objects;

import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;

/**
 * A temporary reservation of a file by one worker.
 *
 * @param token     identifies this claim — not the worker (see {@link LeaseToken})
 * @param holder    worker identifier, for diagnosis only
 * @param expiresAt after this instant the work may be taken over by anyone
 */
public record Lease(LeaseToken token, String holder, Instant expiresAt) {

    public Lease {
        Objects.requireNonNull(token, "lease token");
        Objects.requireNonNull(holder, "lease holder");
        Objects.requireNonNull(expiresAt, "lease expiry");
        if (holder.isBlank()) {
            throw new IllegalArgumentException("A lease holder must be identifiable");
        }
    }
}
