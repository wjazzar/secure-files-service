package com.praxedo.securefiles.infrastructure.security.common.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The service as a <strong>confidential client</strong> of Keycloak — what the
 * browser sessions need (ADR-0012). Read only in {@code oidc} mode.
 *
 * <p>The web adapter reads {@code issuer}, {@code jwk-set-uri} and the timeouts
 * too, through its own properties: adapters do not share classes, only
 * configuration keys.
 *
 * @param issuer    the {@code iss} the identity token must carry — the address
 *                  under which the <em>browser</em> reaches Keycloak
 * @param jwkSetUri where the service fetches the signing keys — the address
 *                  under which the <em>service</em> reaches it
 * @param client    the client registration itself
 */
@ConfigurationProperties("praxedo.security.oidc")
public record OidcClientProperties(URI issuer, URI jwkSetUri, Client client) {

    /**
     * @param clientId         the confidential client registered in the realm
     * @param clientSecret     its secret — from the environment only (rule B-5)
     * @param authorizationUri where the <em>browser</em> is sent to sign in
     * @param tokenUri         where the <em>service</em> redeems codes and refresh tokens
     * @param logoutUri        where the <em>browser</em> is sent to end its
     *                         Keycloak session (OpenID Connect RP-initiated logout)
     * @param redirectUri      where Keycloak sends the browser back;
     *                         {@code {baseUrl}} is the origin the browser used,
     *                         which Keycloak checks against its exact list
     */
    public record Client(String clientId, String clientSecret, URI authorizationUri, URI tokenUri, URI logoutUri,
                         String redirectUri) {

        @Override
        public String toString() {
            return "Client[clientId=" + clientId + ", clientSecret=redacted, authorizationUri=" + authorizationUri
                    + ", tokenUri=" + tokenUri + ", logoutUri=" + logoutUri + ", redirectUri=" + redirectUri + "]";
        }
    }
}
