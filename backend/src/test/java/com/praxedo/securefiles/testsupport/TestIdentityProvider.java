package com.praxedo.securefiles.testsupport;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Keycloak, simulated where it meets the service: its published key set,
 * served by WireMock, and tokens signed here with the matching private key.
 * Everything on the service's side is the production path — the key fetch,
 * the signature, the issuer, the audience, the expiry, and the owner taken
 * from the subject.
 *
 * <p>Every test that starts a Spring context is wired to it
 * ({@link PostgresTestcontainer}): the service has no setting that lets a
 * request through without credentials, so a test that calls the API does it
 * as someone — {@link #USER}, unless the test is about identities.
 *
 * <p>The server and the keys start on first use: a test that never reads them
 * starts nothing.
 */
public final class TestIdentityProvider {

    public static final String ISSUER = "http://keycloak.test/realms/praxedo";
    public static final String AUDIENCE = "praxedo-files-api";
    public static final String CLIENT_ID = "praxedo-web";
    public static final String CLIENT_SECRET = "test-only-client-secret";

    /** Where the realm's OpenID Connect endpoints are served, on the simulated server. */
    public static final String OIDC_PATH = "/realms/praxedo/protocol/openid-connect";

    /** Who the API is called as by default — and who owns the files the fixtures create. */
    public static final OwnerId USER = new OwnerId("test-user");

    private TestIdentityProvider() {
    }

    /**
     * Points the service at this provider: the issuer and audience it expects,
     * where it fetches the keys, and the confidential client the browser's
     * sign-in uses. The browser-facing addresses stay under {@link #ISSUER}, as
     * behind Docker; only what the service itself calls is served.
     */
    static void register(DynamicPropertyRegistry registry) {
        registry.add("praxedo.security.oidc.issuer", () -> ISSUER);
        registry.add("praxedo.security.oidc.audience", () -> AUDIENCE);
        registry.add("praxedo.security.oidc.jwk-set-uri", () -> server().baseUrl() + OIDC_PATH + "/certs");
        registry.add("praxedo.security.oidc.client.client-id", () -> CLIENT_ID);
        registry.add("praxedo.security.oidc.client.client-secret", () -> CLIENT_SECRET);
        registry.add("praxedo.security.oidc.client.authorization-uri", () -> ISSUER + "/protocol/openid-connect/auth");
        registry.add("praxedo.security.oidc.client.token-uri", () -> server().baseUrl() + OIDC_PATH + "/token");
        registry.add("praxedo.security.oidc.client.logout-uri", () -> ISSUER + "/protocol/openid-connect/logout");
    }

    /** The simulated server, for the tests that stub or inspect its token endpoint. */
    public static WireMockServer server() {
        return Server.INSTANCE;
    }

    /** An access token for {@link #USER}, valid for an hour. */
    public static String accessToken() {
        return accessToken(USER.value());
    }

    /** An access token for {@code subject}, valid for an hour. */
    public static String accessToken(String subject) {
        return accessToken(subject, ISSUER, AUDIENCE, Instant.now().plusSeconds(3600));
    }

    public static String accessToken(String subject, String issuer, String audience, Instant expiresAt) {
        return sign(new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(expiresAt.minusSeconds(3600)))
                .expirationTime(Date.from(expiresAt))
                .build());
    }

    /** Signed with the key the service trusts. */
    public static String sign(JWTClaimsSet claims) {
        return sign(claims, Keys.SIGNING);
    }

    /** Signed with another key, while announcing the genuine key's identifier: a forgery. */
    public static String forge(JWTClaimsSet claims) {
        return sign(claims, Keys.FORGER);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(Keys.SIGNING.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class Keys {

        static final RSAKey SIGNING = generate();
        static final RSAKey FORGER = generate();

        private static RSAKey generate() {
            try {
                return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            } catch (JOSEException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class Server {

        static final WireMockServer INSTANCE = start();

        private static WireMockServer start() {
            WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
            server.stubFor(get(urlEqualTo(OIDC_PATH + "/certs"))
                    .willReturn(okJson(new JWKSet(Keys.SIGNING.toPublicJWK()).toString())));
            return server;
        }
    }
}
