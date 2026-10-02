package com.praxedo.securefiles.application.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.praxedo.securefiles.domain.file.valueobject.ContentType;

/**
 * Guesses a media type from the first bytes of a file.
 *
 * <p><strong>Why forty lines and not a library.</strong> The type is
 * <em>descriptive</em>: it feeds an icon and a metadata field. It is never
 * used to serve the content, which always goes out as
 * {@code application/octet-stream} with {@code nosniff}. An imperfect guess
 * therefore has no security consequence — which is precisely what makes a
 * detection library (Tika and its dependency tree) not worth its weight here.
 *
 * <p>The type the client declared is never consulted (rule B-5).
 */
final class ContentSniffer {

    /** Enough for every signature below, and for a fair text heuristic. */
    static final int WINDOW = 512;

    private static final int NUL = 0x00;
    private static final int TAB = 0x09;
    private static final int LINE_FEED = 0x0A;
    private static final int CARRIAGE_RETURN = 0x0D;
    private static final int FIRST_PRINTABLE = 0x20;

    private ContentSniffer() {
    }

    /**
     * Peeks at the head of the stream and puts it back.
     *
     * @param content must support mark/reset — a {@code BufferedInputStream}
     */
    static ContentType detect(InputStream content) {
        if (!content.markSupported()) {
            throw new IllegalArgumentException("Sniffing needs a stream that can be rewound");
        }
        try {
            content.mark(WINDOW);
            byte[] head = content.readNBytes(WINDOW);
            content.reset();
            return ContentType.of(typeOf(head));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static String typeOf(byte[] head) {
        if (startsWith(head, "%PDF-")) {
            return "application/pdf";
        }
        if (startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(head, "GIF87a") || startsWith(head, "GIF89a")) {
            return "image/gif";
        }
        if (startsWith(head, 'P', 'K', 0x03, 0x04) || startsWith(head, 'P', 'K', 0x05, 0x06)) {
            return "application/zip";
        }
        if (startsWith(head, 0x1F, 0x8B)) {
            return "application/gzip";
        }
        if (startsWith(head, 0x7F, 'E', 'L', 'F')) {
            return "application/x-executable";
        }
        if (startsWith(head, "MZ")) {
            return "application/x-msdownload";
        }
        if (startsWith(head, "{\\rtf")) {
            return "application/rtf";
        }
        if (startsWith(head, "<?xml")) {
            return "application/xml";
        }
        return looksLikeText(head) ? "text/plain" : ContentType.OCTET_STREAM.value();
    }

    private static boolean startsWith(byte[] head, String signature) {
        return startsWith(head, signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean startsWith(byte[] head, int... signature) {
        byte[] bytes = new byte[signature.length];
        for (int index = 0; index < signature.length; index++) {
            bytes[index] = (byte) signature[index];
        }
        return startsWith(head, bytes);
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (head[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    /** No NUL byte, and almost nothing below the printable range: plain text. */
    private static boolean looksLikeText(byte[] head) {
        if (head.length == 0) {
            return false;
        }
        int control = 0;
        for (byte value : head) {
            int unsigned = value & 0xFF;
            if (unsigned == NUL) {
                return false;
            }
            boolean whitespace = unsigned == TAB || unsigned == LINE_FEED || unsigned == CARRIAGE_RETURN;
            if (unsigned < FIRST_PRINTABLE && !whitespace) {
                control++;
            }
        }
        return control * 20 < head.length;
    }
}
