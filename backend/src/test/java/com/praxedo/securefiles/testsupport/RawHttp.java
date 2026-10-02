package com.praxedo.securefiles.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * An HTTP client that is allowed to lie.
 *
 * <p>A well-behaved client refuses to send a {@code Content-Length} it does not
 * honour, and always announces a length. The cases worth testing are exactly
 * the ones it refuses: a body cut short, a length announced and never sent, no
 * length at all. They are produced here byte by byte on a raw socket.
 */
public final class RawHttp {

    private RawHttp() {
    }

    /**
     * Sends the given request head and body prefix, optionally closes the
     * sending half of the connection, and returns whatever the server answered.
     *
     * @param closeAfterBody half-close after the body: the server then sees the
     *                       body end early, as when a client dies mid-upload
     */
    public static Answer exchange(int port, String requestHead, byte[] body, boolean closeAfterBody) {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            if (closeAfterBody) {
                socket.shutdownOutput();
            }
            return new Answer(readUntilQuiet(socket.getInputStream()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String readUntilQuiet(InputStream in) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                received.write(buffer, 0, read);
                if (complete(received)) {
                    break;
                }
            }
        } catch (SocketTimeoutException quiet) {
            // The server said everything it had to say and kept the connection open.
        }
        return received.toString(StandardCharsets.UTF_8);
    }

    /** A response whose declared body has fully arrived. */
    private static boolean complete(ByteArrayOutputStream received) {
        String text = received.toString(StandardCharsets.ISO_8859_1);
        int headEnd = text.indexOf("\r\n\r\n");
        if (headEnd < 0) {
            return false;
        }
        String head = text.substring(0, headEnd).toLowerCase(java.util.Locale.ROOT);
        int lengthAt = head.indexOf("content-length:");
        if (lengthAt >= 0) {
            int lineEnd = head.indexOf("\r\n", lengthAt);
            long length = Long.parseLong(head.substring(lengthAt + 15, lineEnd < 0 ? head.length() : lineEnd).strip());
            return text.length() - (headEnd + 4) >= length;
        }
        return text.endsWith("0\r\n\r\n");
    }

    /** The raw answer, with the two things tests look at. */
    public record Answer(String raw) {

        public int status() {
            String[] statusLine = raw.split("\r\n", 2)[0].split(" ");
            return Integer.parseInt(statusLine[1]);
        }

        public boolean mentions(String text) {
            return raw.contains(text);
        }
    }
}
