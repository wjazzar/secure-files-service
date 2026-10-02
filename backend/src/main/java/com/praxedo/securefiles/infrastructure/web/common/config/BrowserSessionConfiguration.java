package com.praxedo.securefiles.infrastructure.web.common.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import com.praxedo.securefiles.infrastructure.web.session.signin.BrowserSessionConfigurer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Browser side: the pieces the sign-in and the session need, each call to
 * Keycloak with explicit timeouts (rule B-7) — Spring Security's defaults have
 * none.
 *
 * <p>The client registration itself is declared by the security adapter, which
 * owns the conversation with Keycloak; it is used here by its id.
 */
@Configuration(proxyBeanMethods = false)
class BrowserSessionConfiguration {

    /** Declared by {@code IdentityProviderConfiguration} in the security adapter. */
    private static final String REGISTRATION_ID = "keycloak";

    /**
     * Spring Session's cookie — the only thing the browser holds.
     *
     * <ul>
     *   <li>{@code HttpOnly}: no script ever reads it;</li>
     *   <li>{@code SameSite=Lax}, not {@code Strict}: the return from Keycloak is
     *       a top-level navigation from another site whenever Keycloak is on
     *       another domain, and the session holds the sign-in in progress that
     *       this return is checked against. {@code Lax} still keeps it off every
     *       cross-site write, which the CSRF token guards as well;</li>
     *   <li>with {@code secure}: {@code Secure} and the {@code __Host-} prefix —
     *       the browser then refuses it from plain HTTP, from another path, and
     *       from a sibling subdomain trying to plant one.</li>
     * </ul>
     */
    @Bean
    DefaultCookieSerializerCustomizer sessionCookie(SecurityProperties properties) {
        boolean secure = properties.cookies().secure();
        return cookie -> {
            cookie.setCookieName((secure ? "__Host-" : "") + "praxedo-session");
            cookie.setUseHttpOnlyCookie(true);
            cookie.setUseSecureCookie(secure);
            cookie.setSameSite("Lax");
            cookie.setCookiePath("/");
        };
    }

    @Bean
    BrowserSessionConfigurer browserSessionConfigurer(ClientRegistrationRepository registrations,
                                                      SecurityProperties properties, JsonMapper json, Clock clock) {
        RestClient tokenCalls = RestClient.builder()
                .requestFactory(requestFactory(properties.oidc()))
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientAuthorizationCodeTokenResponseClient codeExchange = new RestClientAuthorizationCodeTokenResponseClient();
        codeExchange.setRestClient(tokenCalls);
        RestClientRefreshTokenTokenResponseClient refresh = new RestClientRefreshTokenTokenResponseClient();
        refresh.setRestClient(tokenCalls);

        return new BrowserSessionConfigurer(registrations, REGISTRATION_ID, codeExchange, refresh, properties, json,
                clock);
    }

    /**
     * How identity tokens are verified at sign-in: signature by the provider's
     * keys (fetched with timeouts, then cached by the decoder), then Spring
     * Security's OpenID Connect checks — issuer, audience (this client),
     * authorised party, expiry, issue time. The {@code nonce} is checked by the
     * sign-in itself, against the one kept in the session.
     *
     * <p>Found by type by Spring Security's {@code oauth2Login}.
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(SecurityProperties properties) {
        RestTemplate keyFetches = new RestTemplate(requestFactory(properties.oidc()));
        Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();
        return registration -> decoders.computeIfAbsent(registration.getRegistrationId(), id -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .restOperations(keyFetches)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                    new OidcIdTokenValidator(registration)));
            decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
            return decoder;
        });
    }

    /** Every call to Keycloak — key fetches, token endpoint — goes through this one, with rule B-7's timeouts. */
    static JdkClientHttpRequestFactory requestFactory(SecurityProperties.Oidc oidc) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(oidc.connectTimeout()).build());
        factory.setReadTimeout(oidc.readTimeout());
        return factory;
    }
}
