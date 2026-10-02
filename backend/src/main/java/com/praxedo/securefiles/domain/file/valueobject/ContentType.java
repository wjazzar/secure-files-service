package com.praxedo.securefiles.domain.file.valueobject;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Media type of a stored file, <strong>as detected by the server</strong> —
 * never the one declared by the caller (rule B-5).
 *
 * <p>It is descriptive: it feeds the interface's icon and the metadata. It is
 * never used to serve the content, which always goes out as
 * {@code application/octet-stream} with {@code nosniff}. That is what makes an
 * imperfect detection harmless.
 */
public record ContentType(String value) {

    // Declared BEFORE the constant below: a static field initialised after the
    // constant would still be null while the constant's constructor runs.
    private static final Pattern TYPE_SUBTYPE =
            Pattern.compile("^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$");

    public static final ContentType OCTET_STREAM = new ContentType("application/octet-stream");

    public ContentType {
        Objects.requireNonNull(value, "content type");
        if (!TYPE_SUBTYPE.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a media type: " + value);
        }
    }

    public static ContentType of(String value) {
        return new ContentType(value.toLowerCase(Locale.ROOT).strip());
    }

    @Override
    public String toString() {
        return value;
    }
}
