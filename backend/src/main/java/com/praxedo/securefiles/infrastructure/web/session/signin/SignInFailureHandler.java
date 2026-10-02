package com.praxedo.securefiles.infrastructure.web.session.signin;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * A sign-in that did not complete sends the browser back to the interface's
 * login page, with a code it can explain — and nothing more.
 *
 * <p>Wrong {@code state}, a return in a browser that did not start the
 * sign-in, a replayed return, an identity token that fails a check, Keycloak
 * unreachable: the browser learns only that it failed. Which check failed goes
 * to the log, for the operator.
 */
final class SignInFailureHandler implements AuthenticationFailureHandler {

    static final String FAILED = "sign-in-failed";
    static final String CANCELLED = "sign-in-cancelled";

    private static final Logger LOG = LoggerFactory.getLogger(SignInFailureHandler.class);
    private static final String LOGIN_PAGE = "/login?error=";
    private static final Pattern LOGGABLE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    static final String UNRECOGNISED = "unrecognised";

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException failure) throws IOException {
        String reason = failure instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : failure.getClass().getSimpleName();
        LOG.warn("Sign-in failed: {}", loggable(reason));
        // Forget where this attempt meant to go: the next one says it again.
        SignInSuccessHandler.returnTo(request.getSession(false));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.sendRedirect(LOGIN_PAGE + (OAuth2ErrorCodes.ACCESS_DENIED.equals(reason) ? CANCELLED : FAILED));
    }

    /**
     * The code as the log may show it. It can come from the caller: the
     * {@code error} parameter of the return address is read before the
     * {@code state} is checked, so anyone who started a sign-in chooses it. A
     * real code is a short token; anything else — a line break forging a log
     * entry, above all — is logged as {@value #UNRECOGNISED}.
     */
    static String loggable(String code) {
        return code != null && LOGGABLE.matcher(code).matches() ? code : UNRECOGNISED;
    }
}
