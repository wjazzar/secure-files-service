package com.praxedo.securefiles.infrastructure.web.session.signin;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import com.praxedo.securefiles.infrastructure.web.common.config.SecurityProperties;
import com.praxedo.securefiles.infrastructure.web.session.dto.LogoutResponse;
import com.praxedo.securefiles.infrastructure.web.session.filter.SessionRevalidationFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Browser sessions, as one piece of the security configuration (ADR-0012):
 * Spring Security's OAuth2 client, with the service as Keycloak's
 * confidential client, and the HTTP session kept by Spring Session in
 * PostgreSQL.
 *
 * <pre>
 * GET  /api/v1/auth/login?redirect=/…   → 302 to Keycloak (state, nonce, PKCE S256)
 * GET  /api/v1/auth/callback?code&amp;state → code redeemed with the client secret and the verifier,
 *                                         identity token verified, new session id, 302 back
 * any  /api/…  with the session cookie   → the signed-in user, revalidated with Keycloak
 *                                         when the access token has expired
 * POST /api/v1/auth/logout               → session closed; the address that ends it at Keycloak
 * </pre>
 *
 * <p>Everything protocol-related is Spring Security's. What this class adds is
 * configuration, and three small pieces: where to go back after signing in
 * ({@link SignInRequestResolver}), how the tokens are kept in the session
 * without the client secret ({@link SessionAuthorizedClients}), and the
 * revalidation ({@link SessionRevalidationFilter}).
 *
 * <p><strong>CSRF.</strong> A cookie is sent by the browser on its own, so a
 * write carried by it must prove it comes from the interface: Spring
 * Security's single-page-application scheme — a readable {@code XSRF-TOKEN}
 * cookie that the interface echoes in {@code X-XSRF-TOKEN}, which another site
 * cannot read. It applies to writes that carry the session cookie; a write
 * without a session has nothing to forge and gets the contract's {@code 401},
 * not a {@code 403}.
 */
public final class BrowserSessionConfigurer extends AbstractHttpConfigurer<BrowserSessionConfigurer, HttpSecurity> {

    public static final String LOGIN_PATH = SignInRequestResolver.LOGIN_PATH;
    public static final String CALLBACK_PATH = "/api/v1/auth/callback";
    public static final String LOGOUT_PATH = "/api/v1/auth/logout";

    /** The interface's login page, told that the user signed out. Registered in the realm. */
    static final String SIGNED_OUT_PAGE = "/login?signed-out";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ClientRegistrationRepository registrations;
    private final String registrationId;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeExchange;
    private final SessionAuthorizedClients authorizedClients;
    private final SessionRevalidationFilter revalidation;
    private final boolean secureCookies;
    private final JsonMapper json;

    /**
     * @param registrationId the one client registration of the realm
     * @param codeExchange   the code exchange, built with timeouts (rule B-7)
     * @param refresh        the refresh grant, built with timeouts (rule B-7)
     */
    public BrowserSessionConfigurer(ClientRegistrationRepository registrations, String registrationId,
                                    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeExchange,
                                    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refresh,
                                    SecurityProperties properties, JsonMapper json, Clock clock) {
        if (registrations.findByRegistrationId(registrationId) == null) {
            throw new IllegalStateException("No client registration " + registrationId);
        }
        this.registrations = registrations;
        this.registrationId = registrationId;
        this.codeExchange = codeExchange;
        this.authorizedClients = new SessionAuthorizedClients(registrations);
        this.secureCookies = properties.cookies().secure();
        this.json = json;

        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(grant -> grant.accessTokenResponseClient(refresh))
                .build());
        this.revalidation = new SessionRevalidationFilter(manager, authorizedClients, registrationId,
                properties.revalidation(), clock);
    }

    @Override
    public void init(HttpSecurity http) {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(secureCookies).sameSite("Strict").path("/"));

        http.csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfTokens)
                        .requireCsrfProtectionMatcher(BrowserSessionConfigurer::writesWithTheSessionCookie))
                .oauth2Login(login -> login
                        // The interface's page. Declaring it also stops Spring Security
                        // from generating a login page of its own.
                        .loginPage("/login")
                        .clientRegistrationRepository(registrations)
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(new SignInRequestResolver(registrations, registrationId)))
                        .redirectionEndpoint(redirection -> redirection.baseUri(CALLBACK_PATH))
                        .tokenEndpoint(token -> token.accessTokenResponseClient(codeExchange))
                        .authorizedClientRepository(authorizedClients)
                        .successHandler(new SignInSuccessHandler())
                        .failureHandler(new SignInFailureHandler()))
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler(endAtKeycloak()));
    }

    @Override
    public void configure(HttpSecurity http) {
        // After the session has been read, before Spring Security settles that
        // the request is unauthenticated: a session Keycloak ended is refused
        // like no session.
        http.addFilterBefore(revalidation, AnonymousAuthenticationFilter.class);
    }

    /**
     * OpenID Connect RP-initiated logout: the session here is already closed —
     * Spring Security's logout invalidated it and removed the cookie — and the
     * browser must now visit Keycloak, which ends its own session and sends it
     * back to the interface. The interface signs out with a {@code fetch}, which
     * cannot follow a redirection to another origin: the address is returned as
     * JSON, and the interface navigates to it.
     */
    private OidcClientInitiatedLogoutSuccessHandler endAtKeycloak() {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri("{baseUrl}" + SIGNED_OUT_PAGE);
        // Signing out without a session is not an error: straight to the login page.
        handler.setDefaultTargetUrl(SIGNED_OUT_PAGE);
        handler.setRedirectStrategy(this::answerWithTheAddress);
        return handler;
    }

    private void answerWithTheAddress(HttpServletRequest request, HttpServletResponse response, String url)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new LogoutResponse(url));
    }

    /** A write carried by the session cookie, valid or not. */
    private static boolean writesWithTheSessionCookie(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod()) && request.getRequestedSessionId() != null;
    }
}
