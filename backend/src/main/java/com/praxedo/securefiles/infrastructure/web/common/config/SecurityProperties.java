package com.praxedo.securefiles.infrastructure.web.common.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may call the API: a browser session or a Keycloak access token, always —
 * there is no setting that lets a request through without one (ADR-0014). Its
 * subject owns the files.
 *
 * @param oidc         how tokens are checked
 * @param cookies      how the browser's cookies are written
 * @param revalidation how a browser session follows Keycloak's verdict
 */
@ConfigurationProperties("praxedo.security")
public record SecurityProperties(Oidc oidc, Cookies cookies, Revalidation revalidation) {

    public SecurityProperties {
        cookies = cookies == null ? new Cookies(true) : cookies;
        revalidation = revalidation == null
                ? new Revalidation(Duration.ofMinutes(30), Duration.ofSeconds(30))
                : revalidation;
    }

    /**
     * Two addresses for the same identity provider, on purpose.
     *
     * @param issuer         the {@code iss} claim tokens must carry — the address
     *                       under which the <em>browser</em> reaches Keycloak
     * @param jwkSetUri      where the service fetches the signing keys — the
     *                       address under which the <em>service</em> reaches it.
     *                       Behind Docker they differ ({@code localhost:8081} for
     *                       the one, {@code keycloak:8080} for the other), and
     *                       confusing them is the classic cause of every token
     *                       being refused
     * @param audience       the {@code aud} value an access token must hold: a
     *                       token issued for another application is refused
     * @param connectTimeout rule B-7, for every call to Keycloak
     * @param readTimeout    rule B-7, for every call to Keycloak
     */
    public record Oidc(URI issuer, URI jwkSetUri, String audience, Duration connectTimeout, Duration readTimeout) {
    }

    /**
     * @param secure the {@code Secure} attribute of the session and CSRF cookies,
     *               and with it the {@code __Host-} prefix of the session cookie,
     *               which forbids a sibling subdomain from planting it.
     *               {@code false} only for plain HTTP in local development
     */
    public record Cookies(boolean secure) {
    }

    /**
     * When Keycloak does not answer, a browser session is served on its last
     * verdict — for a bounded time, and without asking again on every request.
     *
     * @param maxWithoutVerdict how long after Keycloak's last verdict a session
     *                          may still be served while Keycloak is silent
     * @param retryDelay        how long before asking Keycloak again after it
     *                          failed to answer
     */
    public record Revalidation(Duration maxWithoutVerdict, Duration retryDelay) {
    }
}
