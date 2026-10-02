package com.praxedo.securefiles.domain.file.valueobject;

import java.text.Normalizer;
import java.util.Objects;

/**
 * The original name of a file, as supplied by the caller — sanitised.
 *
 * <p>This name is <strong>metadata only</strong>. It is never a storage key
 * and never part of a path (rule B-3): the key is a generated UUID. Sanitising
 * is therefore not what protects the storage; it protects everything
 * downstream that will display, log or re-serve the name.
 *
 * <p>What is removed, and why:
 * <ul>
 *   <li><strong>Path separators</strong> — {@code ../}, {@code /}, {@code \}
 *       and the Windows drive prefix. Cheap insurance against any future code
 *       that would be tempted to use the name as a path.</li>
 *   <li><strong>Control characters</strong>, including NUL — they truncate C
 *       strings, break log lines and corrupt headers.</li>
 *   <li><strong>Bidirectional overrides</strong> (U+202A..U+202E, U+2066..U+2069)
 *       — the classic "invoice&#92;u202Egnp.exe" trick, which displays as a PNG
 *       and executes as an EXE.</li>
 * </ul>
 *
 * <p>The result is capped at 255 characters, keeping the extension: a truncated
 * name that loses its extension confuses users far more than a shortened one.
 */
public record FileName(String value) {

    public static final int MAX_LENGTH = 255;

    private static final String FALLBACK = "unnamed";

    public FileName {
        Objects.requireNonNull(value, "file name");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A file name cannot be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("A file name cannot exceed " + MAX_LENGTH + " characters");
        }
        if (!value.equals(sanitise(value))) {
            throw new IllegalArgumentException("A file name must be sanitised before it becomes a FileName");
        }
    }

    /**
     * Turns whatever the caller sent into a name safe to store, display and log.
     *
     * @throws IllegalArgumentException if nothing usable remains
     */
    public static FileName sanitised(String raw) {
        Objects.requireNonNull(raw, "file name");
        String cleaned = sanitise(raw);
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("The file name contains nothing usable");
        }
        return new FileName(cleaned);
    }

    private static String sanitise(String raw) {
        String normalised = Normalizer.normalize(raw, Normalizer.Form.NFC);

        // Keep the last path segment: "../../etc/passwd" becomes "passwd".
        int lastSeparator = Math.max(normalised.lastIndexOf('/'), normalised.lastIndexOf('\\'));
        String segment = lastSeparator >= 0 ? normalised.substring(lastSeparator + 1) : normalised;

        StringBuilder cleaned = new StringBuilder(segment.length());
        segment.codePoints()
                .filter(codePoint -> !isControl(codePoint) && !isBidirectionalOverride(codePoint))
                .forEach(cleaned::appendCodePoint);

        String result = cleaned.toString().strip();
        // "." and ".." survive the filtering above but name no file.
        if (result.equals(".") || result.equals("..")) {
            return FALLBACK;
        }
        return truncateKeepingExtension(result);
    }

    private static boolean isControl(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL
                || Character.getType(codePoint) == Character.FORMAT;
    }

    private static boolean isBidirectionalOverride(int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static String truncateKeepingExtension(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = (dot > 0 && name.length() - dot <= 16) ? name.substring(dot) : "";
        return name.substring(0, MAX_LENGTH - extension.length()) + extension;
    }

    @Override
    public String toString() {
        return value;
    }
}
