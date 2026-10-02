package com.praxedo.securefiles.infrastructure.web.session.signin;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * {@code GET /api/v1/auth/login?redirect=…} — builds the redirection to
 * Keycloak.
 *
 * <p>Spring Security's resolver does the protocol: a fresh {@code state}
 * (binds the return to this attempt), a {@code nonce} (binds the identity token
 * to it), and PKCE with {@code S256} (binds the code to a verifier that never
 * leaves the service). Spring Security then keeps the request in the session,
 * for the return to be checked against. This class only fixes the address —
 * one provider, so no provider in the path — and records in the session where
 * the browser wants to go back to, sanitised.
 */
final class SignInRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String LOGIN_PATH = "/api/v1/auth/login";

    /** Session attribute read, then removed, by the success and failure handlers. */
    static final String RETURN_TO = SignInRequestResolver.class.getName() + ".returnTo";

    private final RequestMatcher login = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, LOGIN_PATH);
    private final DefaultOAuth2AuthorizationRequestResolver protocol;
    private final String registrationId;

    SignInRequestResolver(ClientRegistrationRepository registrations, String registrationId) {
        this.protocol = new DefaultOAuth2AuthorizationRequestResolver(registrations, LOGIN_PATH);
        this.registrationId = registrationId;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return login.matches(request) ? resolve(request, registrationId) : null;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest authorization = protocol.resolve(request, clientRegistrationId);
        if (authorization != null) {
            request.getSession().setAttribute(RETURN_TO, ReturnTo.sanitize(request.getParameter("redirect")));
        }
        return authorization;
    }
}
