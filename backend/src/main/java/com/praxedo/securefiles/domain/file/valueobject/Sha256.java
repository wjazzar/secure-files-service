package com.praxedo.securefiles.domain.file.valueobject;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A SHA-256 digest, lowercase hexadecimal.
 *
 * <p>It is a value object rather than a {@code String} because it is compared
 * for a security decision: the attestation that authorises promotion is bound
 * to <em>this</em> digest. A comparison between two {@code String}s of unknown
 * shape is a comparison nobody can review.
 */
public record Sha256(String value) {

    private static final Pattern LOWERCASE_HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    public Sha256 {
        Objects.requireNonNull(value, "digest");
        if (!LOWERCASE_HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("A SHA-256 digest must be 64 lowercase hexadecimal characters");
        }
    }

    /** Accepts any case, stores lowercase — engines differ on this. */
    public static Sha256 of(String hex) {
        Objects.requireNonNull(hex, "digest");
        return new Sha256(hex.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
