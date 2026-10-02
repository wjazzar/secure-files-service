package com.praxedo.securefiles.infrastructure.web.common.config;

import java.util.Collection;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestTemplate;

import com.praxedo.securefiles.infrastructure.web.common.error.ProblemAccessDeniedHandler;
import com.praxedo.securefiles.infrastructure.web.common.error.ProblemAuthenticationEntryPoint;
import com.praxedo.securefiles.infrastructure.web.session.signin.BrowserSessionConfigurer;

import tools.jackson.databind.json.JsonMapper;

/**
 * The API's access rules. There is no way to switch them off: every request
 * to {@code /api} is authenticated, and the owner of a file is always the
 * subject Keycloak vouched for (ADR-0014).
 *
 * <p>The same routes are reached two ways, by two chains that never mix:
 * <ul>
 *   <li><strong>third-party systems</strong> — any request that carries an
 *       {@code Authorization} header: a bearer access token checked by
 *       signature, issuer, audience and expiry (ADR-0014). Stateless: no
 *       session is ever created, no cookie ever set, no CSRF token asked — and
 *       any node answers, since the token carries everything;</li>
 *   <li><strong>the web interface</strong> — every other request: a browser
 *       session opened by Spring Security's OAuth2 login, the service being
 *       Keycloak's confidential client, and kept by Spring Session in
 *       PostgreSQL so that every node sees it (ADR-0012,
 *       {@link BrowserSessionConfigurer}).</li>
 * </ul>
 * Everything under {@code /api} requires one of them — downloads included: a
 * browser's native download carries its session cookie by itself, so there is
 * no second credential to issue (ADR-0013) — except the sign-in endpoints
 * themselves. The actuator stays open — probes must answer without
 * credentials — but on its own port ({@code management.server.port}), never
 * the API's: through the public address, {@code /actuator} serves nothing — a
 * {@code 401} without credentials, a {@code 404} with them (audit S-08).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration {

    private static final String ACTUATOR = "/actuator/**";

    /**
     * The protected resource metadata (RFC 9728) that every {@code 401} points
     * to in its {@code WWW-Authenticate} challenge. Served by the resource
     * server, hence by the stateless chain — and asked for without a token.
     */
    private static final RequestMatcher RESOURCE_METADATA =
            PathPatternRequestMatcher.withDefaults().matcher("/.well-known/oauth-protected-resource/**");

    /** Third-party systems: a bearer token, and nothing kept between two requests. */
    @Bean
    @Order(1)
    SecurityFilterChain thirdPartySystems(HttpSecurity http, JwtDecoder decoder, JsonMapper json) throws Exception {
        ProblemAuthenticationEntryPoint unauthenticated = new ProblemAuthenticationEntryPoint(json);
        return stateless(http)
                .securityMatcher(request -> carriesAuthorization(request) || RESOURCE_METADATA.matches(request))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(ACTUATOR).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(unauthenticated))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthenticated))
                .build();
    }

    /** The web interface: every request without an {@code Authorization} header. */
    @Bean
    @Order(2)
    SecurityFilterChain webInterface(HttpSecurity http, BrowserSessionConfigurer browserSessions, JsonMapper json)
            throws Exception {
        return http
                .with(browserSessions, Customizer.withDefaults())
                // The interface is a single-page application: after signing in it
                // goes back to where it asked to, never to a replayed request.
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(ACTUATOR).permitAll()
                        .requestMatchers(BrowserSessionConfigurer.LOGIN_PATH, BrowserSessionConfigurer.CALLBACK_PATH)
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint(json))
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(json)))
                .build();
    }

    /**
     * Signature through the provider's published keys, then three checks on
     * the claims: the issuer, the audience, and the validity window (with the
     * library's default one-minute clock skew).
     *
     * <p>The keys are fetched lazily and cached by the decoder: the service
     * starts even when the identity provider is not up yet.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        SecurityProperties.Oidc oidc = properties.oidc();
        // Rule B-7: connection AND read timeouts, both explicit — the same client as the browser sessions'.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oidc.jwkSetUri().toString())
                .restOperations(new RestTemplate(BrowserSessionConfiguration.requestFactory(oidc)))
                .build();
        decoder.setJwtValidator(validator(oidc));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(SecurityProperties.Oidc oidc) {
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<Collection<String>>(
                "aud", audiences -> audiences != null && audiences.contains(oidc.audience()));
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(oidc.issuer().toString()), audience);
    }

    /** Nothing about a caller outlives its request: no session, no saved request, no cookie. */
    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
    }

    /**
     * An API client and a browser never mix their credentials: a request that
     * brings its own is judged on it alone, whatever cookie comes along.
     */
    private static boolean carriesAuthorization(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    }
}
