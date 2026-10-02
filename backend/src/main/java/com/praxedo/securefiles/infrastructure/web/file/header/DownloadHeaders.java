package com.praxedo.securefiles.infrastructure.web.file.header;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.praxedo.securefiles.application.file.model.ByteRange;

/**
 * The two HTTP headers the download path has to get exactly right — kept out
 * of the controller so that each can be read, and tested, on its own.
 */
public final class DownloadHeaders {

    /** {@code bytes=first-} or {@code bytes=first-last}: the forms used to resume a download. */
    private static final Pattern SINGLE_RANGE = Pattern.compile("^bytes=(\\d{1,19})-(\\d{0,19})$");

    private DownloadHeaders() {
    }

    /**
     * {@code Content-Disposition}, RFC 6266 / 8187: an ASCII fallback for old
     * clients, and the exact UTF-8 name for the others. The fallback replaces
     * what could break the header — quotes, backslashes, control characters —
     * and every non-ASCII character.
     */
    public static String attachment(String filename) {
        StringBuilder fallback = new StringBuilder(filename.length());
        filename.codePoints().forEach(codePoint -> fallback.append(
                codePoint >= 0x20 && codePoint < 0x7F && codePoint != '"' && codePoint != '\\'
                        ? (char) codePoint : '_'));
        // URLEncoder leaves '*' alone, which RFC 8187 does not allow in a value: encoded too.
        String exact = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + exact;
    }

    /**
     * {@code Range}: a single range is honoured; anything else — several
     * ranges, a suffix range, a malformed header — is ignored and the whole
     * file is served, as RFC 9110 allows. Several ranges would require a
     * multipart body assembled in memory, which rule B-1 forbids.
     */
    public static ByteRange rangeOf(String header) {
        if (header == null) {
            return ByteRange.WHOLE_OBJECT;
        }
        Matcher matcher = SINGLE_RANGE.matcher(header.strip());
        if (!matcher.matches()) {
            return ByteRange.WHOLE_OBJECT;
        }
        try {
            long first = Long.parseLong(matcher.group(1));
            if (matcher.group(2).isEmpty()) {
                return ByteRange.from(first);
            }
            long last = Long.parseLong(matcher.group(2));
            return last < first ? ByteRange.WHOLE_OBJECT : ByteRange.of(first, last);
        } catch (NumberFormatException overflow) {
            return ByteRange.WHOLE_OBJECT;
        }
    }
}
