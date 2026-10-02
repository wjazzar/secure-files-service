package com.praxedo.securefiles.infrastructure.security.common.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;

/**
 * The service registered as Keycloak's <strong>confidential client</strong>:
 * a client id and a secret, the authorization code flow with PKCE, and no
 * token ever handed to the browser (ADR-0012). Spring Security's OAuth2 client
 * does everything else with this registration: the sign-in, the refresh
 * grant, the OpenID Connect sign-out.
 *
 * <p>The registration is declared field by field rather than discovered from
 * the issuer: discovery would have to be fetched from the address the
 * <em>browser</em> uses, which the service cannot reach behind Docker, and it
 * would make the service's start depend on Keycloak's. Here, nothing is fetched
 * until a user signs in.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcClientProperties.class)
class IdentityProviderConfiguration {

    /** The one registration: the web adapter's sign-in endpoints use it by this id. */
    static final String REGISTRATION_ID = "keycloak";

    /** The secret of the development realm ({@code docker-compose.yml}): public, hence never for real use. */
    static final String DEVELOPMENT_SECRET = "praxedo-local-web-secret-do-not-reuse";

    private static final Logger LOG = LoggerFactory.getLogger(IdentityProviderConfiguration.class);

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(OidcClientProperties properties) {
        OidcClientProperties.Client client = requireClient(properties);
        if (DEVELOPMENT_SECRET.equals(client.clientSecret())) {
            LOG.warn("Client {} uses the secret of the local development realm, which is written in the "
                    + "repository: set OIDC_CLIENT_SECRET anywhere but a developer machine", client.clientId());
        }
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Keycloak")
                .clientId(client.clientId())
                .clientSecret(client.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // PKCE even for a confidential client (OAuth 2.1, RFC 9700 §2.1.1):
                // a code intercepted on its way back is worthless without the verifier.
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .redirectUri(client.redirectUri())
                .scope(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL)
                .authorizationUri(client.authorizationUri().toString())
                .tokenUri(client.tokenUri().toString())
                .jwkSetUri(properties.jwkSetUri().toString())
                // Without it, the identity token's issuer would go unchecked.
                .issuerUri(properties.issuer().toString())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                // What discovery would have provided, and the OpenID Connect
                // sign-out needs: where to send the browser to end its Keycloak session.
                .providerConfigurationMetadata(Map.of("end_session_endpoint", client.logoutUri().toString()))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /** Fails the start rather than the first sign-in: a missing secret is a deployment error. */
    private static OidcClientProperties.Client requireClient(OidcClientProperties properties) {
        OidcClientProperties.Client client = properties.client();
        if (client == null || client.clientId() == null || client.clientId().isBlank()) {
            throw new IllegalStateException("The service needs praxedo.security.oidc.client.client-id (OIDC_CLIENT_ID)");
        }
        if (client.clientSecret() == null || client.clientSecret().isBlank()) {
            throw new IllegalStateException(
                    "The service needs praxedo.security.oidc.client.client-secret (OIDC_CLIENT_SECRET), "
                            + "from the environment — never from the repository");
        }
        return client;
    }
}
