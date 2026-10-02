package com.praxedo.securefiles.domain.file.valueobject;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one <em>claim</em> — not one worker.
 *
 * <p>The distinction is the whole point. A frozen worker whose lease expired
 * can legitimately claim the same file again; if the verdict were guarded by
 * the worker id, its stale thread would be allowed to overwrite the fresh
 * verdict. Guarded by the token of a claim, the stale write matches zero rows.
 */
public record LeaseToken(UUID value) {

    public LeaseToken {
        Objects.requireNonNull(value, "lease token");
    }

    public static LeaseToken random() {
        return new LeaseToken(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
