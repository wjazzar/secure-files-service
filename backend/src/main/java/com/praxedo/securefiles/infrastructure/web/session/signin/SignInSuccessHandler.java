package com.praxedo.securefiles.infrastructure.web.session.signin;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * The end of a successful sign-in: by the time this runs, Spring Security has
 * checked the {@code state}, redeemed the code with the client secret and the
 * PKCE verifier, verified the identity token — signature, issuer, audience,
 * expiry and {@code nonce} — given the session a new identifier, and stored
 * the signed-in user and the tokens in it. None of the tokens reaches the
 * browser: it only holds the session cookie.
 *
 * <p>What is left is to send the browser back where it was going.
 */
final class SignInSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SignInSuccessHandler.class);

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        LOG.info("Session opened for {}", authentication.getName());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.sendRedirect(returnTo(request.getSession(false)));
    }

    /** Where the sign-in was asked to go back to — already sanitised — at most once. */
    static String returnTo(HttpSession session) {
        if (session == null) {
            return ReturnTo.HOME;
        }
        Object returnTo = session.getAttribute(SignInRequestResolver.RETURN_TO);
        session.removeAttribute(SignInRequestResolver.RETURN_TO);
        return returnTo instanceof String path ? path : ReturnTo.HOME;
    }
}
