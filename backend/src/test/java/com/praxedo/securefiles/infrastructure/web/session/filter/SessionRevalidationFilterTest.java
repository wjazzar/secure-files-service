package com.praxedo.securefiles.infrastructure.web.session.filter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.praxedo.securefiles.infrastructure.web.common.config.SecurityProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a signed-in request becomes, for each answer Keycloak can give — or not
 * give. Spring Security's manager is replaced by a script; the clock is fixed.
 */
class SessionRevalidationFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final Duration MAX_WITHOUT_VERDICT = Duration.ofMinutes(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final ClientRegistration KEYCLOAK = ClientRegistration.withRegistrationId("keycloak")
            .clientId("praxedo-web")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/api/v1/auth/callback")
            .authorizationUri("http://keycloak.test/auth")
            .tokenUri("http://keycloak.test/token")
            .build();

    private final OAuth2AuthorizedClientRepository tokens = new HttpSessionOAuth2AuthorizedClientRepository();
    private final AtomicInteger calls = new AtomicInteger();
    private final MockHttpSession session = new MockHttpSession();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final OAuth2AuthenticationToken signedIn = new OAuth2AuthenticationToken(
            new DefaultOAuth2User(List.of(), Map.of("sub", "alice-sub"), "sub"), List.of(), "keycloak");

    @BeforeEach
    void signIn() {
        request.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(signedIn);
    }

    @AfterEach
    void forget() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a request without a session is left alone: Keycloak is not asked")
    void without_a_session() throws Exception {
        SecurityContextHolder.clearContext();

        assertThat(run(answering(client(NOW.plusSeconds(300))))).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a valid access token, or a renewed one: the request goes on signed in")
    void vouched_for() throws Exception {
        assertThat(run(answering(client(NOW.plusSeconds(300))))).isSameAs(signedIn);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    @DisplayName("⭐ refused by Keycloak (invalid_grant): the session is closed and the request is unauthenticated")
    void refused() throws Exception {
        Authentication seen = run(failing("invalid_grant"));

        assertThat(seen).isNull();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("⭐ Keycloak silent: served on the last verdict, and not asked again before the retry delay")
    void silent_within_the_bound() throws Exception {
        storeTokensIssuedAt(NOW.minus(Duration.ofMinutes(10)));

        assertThat(run(failing("invalid_token_response"))).isSameAs(signedIn);
        assertThat(session.getAttribute(SessionRevalidationFilter.NEXT_ATTEMPT)).isEqualTo(NOW.plus(RETRY_DELAY));

        assertThat(run(failing("invalid_token_response"))).isSameAs(signedIn);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("⭐ Keycloak silent for longer than the bound: the session is closed")
    void silent_beyond_the_bound() throws Exception {
        storeTokensIssuedAt(NOW.minus(MAX_WITHOUT_VERDICT).minusSeconds(1));

        assertThat(run(failing("invalid_token_response"))).isNull();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("once Keycloak answers again, the retry delay is forgotten")
    void answering_again() throws Exception {
        session.setAttribute(SessionRevalidationFilter.NEXT_ATTEMPT, NOW.minusSeconds(1));

        assertThat(run(answering(client(NOW.plusSeconds(300))))).isSameAs(signedIn);
        assertThat(session.getAttribute(SessionRevalidationFilter.NEXT_ATTEMPT)).isNull();
    }

    @Test
    @DisplayName("nothing to revalidate with — no tokens, or an expired one without a refresh token: closed")
    void nothing_to_revalidate_with() throws Exception {
        assertThat(run(answering(null))).isNull();

        MockHttpSession another = new MockHttpSession();
        request.setSession(another);
        SecurityContextHolder.getContext().setAuthentication(signedIn);
        assertThat(run(answering(client(NOW.minusSeconds(1))))).isNull();
        assertThat(another.isInvalid()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Runs the filter once; returns who the rest of the chain sees. */
    private Authentication run(OAuth2AuthorizedClientManager manager) throws Exception {
        SessionRevalidationFilter filter = new SessionRevalidationFilter(manager, tokens, "keycloak",
                new SecurityProperties.Revalidation(MAX_WITHOUT_VERDICT, RETRY_DELAY),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication());
        // A fresh request each time, sharing the session, as two calls of one browser would.
        MockHttpServletRequest next = new MockHttpServletRequest("GET", "/api/v1/files");
        next.setSession(request.getSession(false));
        filter.doFilter(next, response, chain);
        return seen.get();
    }

    private OAuth2AuthorizedClientManager answering(OAuth2AuthorizedClient client) {
        return authorize -> {
            calls.incrementAndGet();
            return client;
        };
    }

    private OAuth2AuthorizedClientManager failing(String errorCode) {
        return authorize -> {
            calls.incrementAndGet();
            throw new ClientAuthorizationException(new OAuth2Error(errorCode), "keycloak");
        };
    }

    private void storeTokensIssuedAt(Instant issuedAt) {
        tokens.saveAuthorizedClient(new OAuth2AuthorizedClient(KEYCLOAK, "alice-sub",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", issuedAt, issuedAt.plusSeconds(300)),
                new OAuth2RefreshToken("rt", issuedAt)), signedIn, request, response);
    }

    private static OAuth2AuthorizedClient client(Instant expiresAt) {
        return new OAuth2AuthorizedClient(KEYCLOAK, "alice-sub",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", expiresAt.minusSeconds(300), expiresAt));
    }
}
