package com.praxedo.securefiles.infrastructure.web.session.signin;

import java.io.Serializable;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/**
 * The tokens Keycloak issued for a browser session, kept in that session —
 * and nothing else.
 *
 * <p>Spring Security's own {@code HttpSessionOAuth2AuthorizedClientRepository}
 * stores the whole {@link OAuth2AuthorizedClient}, and with it its
 * {@link ClientRegistration}, <em>client secret included</em>. With the session
 * in PostgreSQL, every session row would then hold the secret next to a
 * refresh token — the one pairing that makes the refresh token usable. Here
 * only the tokens are stored; the registration is looked up again on load.
 */
final class SessionAuthorizedClients implements OAuth2AuthorizedClientRepository {

    private static final String ATTRIBUTE = SessionAuthorizedClients.class.getName() + ".tokens";

    private final ClientRegistrationRepository registrations;

    SessionAuthorizedClients(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                     Authentication principal,
                                                                     HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof Tokens tokens)
                || !tokens.registrationId().equals(clientRegistrationId)) {
            return null;
        }
        ClientRegistration registration = registrations.findByRegistrationId(clientRegistrationId);
        return registration == null
                ? null
                : (T) new OAuth2AuthorizedClient(registration, tokens.principalName(), tokens.accessToken(),
                        tokens.refreshToken());
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                     HttpServletRequest request, HttpServletResponse response) {
        request.getSession().setAttribute(ATTRIBUTE, new Tokens(
                authorizedClient.getClientRegistration().getRegistrationId(), authorizedClient.getPrincipalName(),
                authorizedClient.getAccessToken(), authorizedClient.getRefreshToken()));
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
        }
    }

    /** What a session keeps: whose tokens, and the tokens. {@code refreshToken} may be null. */
    private record Tokens(String registrationId, String principalName, OAuth2AccessToken accessToken,
                          OAuth2RefreshToken refreshToken) implements Serializable {
    }
}
