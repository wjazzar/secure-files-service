package com.praxedo.securefiles.infrastructure.web.session.signin;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where the browser goes back to after signing in — a path of this
 * application, and nothing else.
 *
 * <p>The target arrives in a query parameter anyone can write, and it ends up
 * in a {@code Location} header: unchecked, it would turn the sign-in into an
 * open redirection towards a look-alike site. Only a local absolute path is
 * kept; anything else — another origin, a scheme, {@code //host},
 * {@code /\host} that some browsers read as {@code //host}, control characters —
 * becomes {@code /}.
 */
final class ReturnTo {

    static final String HOME = "/";
    private static final int MAX_LENGTH = 2048;

    private ReturnTo() {
    }

    static String sanitize(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH
                || candidate.charAt(0) != '/' || candidate.startsWith("//")) {
            return HOME;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (character == '\\' || Character.isISOControl(character) || Character.isWhitespace(character)) {
                return HOME;
            }
        }
        try {
            URI uri = new URI(candidate);
            return uri.getScheme() == null && uri.getRawAuthority() == null ? candidate : HOME;
        } catch (URISyntaxException malformed) {
            return HOME;
        }
    }
}
