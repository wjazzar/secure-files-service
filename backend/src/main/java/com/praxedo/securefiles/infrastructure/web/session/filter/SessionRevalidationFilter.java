package com.praxedo.securefiles.infrastructure.web.session.filter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.web.filter.OncePerRequestFilter;

import com.praxedo.securefiles.infrastructure.web.common.config.SecurityProperties;

/**
 * Keycloak stays the authority over a browser session.
 *
 * <p>A session is trusted for as long as the access token Keycloak issued with
 * it. Past that, the next request asks Spring Security to renew it — the
 * refresh grant, authenticated by the client secret — and Keycloak's answer
 * decides:
 * <ul>
 *   <li><strong>renewed</strong>: the session goes on for another access-token
 *       lifetime; the new tokens are written to the shared session, where every
 *       node reads them;</li>
 *   <li><strong>refused</strong> ({@code invalid_grant}: signed out, disabled or
 *       expired in Keycloak): the session is closed here too, and the request
 *       goes on unauthenticated — to the contract's {@code 401}. A user
 *       disabled in Keycloak loses access within one access-token lifetime;</li>
 *   <li><strong>no answer</strong> (Keycloak down, a timeout, a {@code 5xx}):
 *       the session is served on its last verdict, for
 *       {@code maxWithoutVerdict} at most, and Keycloak is asked again every
 *       {@code retryDelay} rather than on every request. Availability for
 *       signed-in users, bounded in time.</li>
 * </ul>
 *
 * <p>Two nodes may renew the same session at the same moment. The realm does
 * not rotate refresh tokens (ADR-0012), so both renewals succeed and the last
 * one written wins; with rotation, the second would be refused and sign the
 * user out.
 */
public final class SessionRevalidationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRevalidationFilter.class);

    /** Session attribute: while Keycloak is silent, no new attempt before this instant. */
    static final String NEXT_ATTEMPT = SessionRevalidationFilter.class.getName() + ".nextAttempt";

    private final OAuth2AuthorizedClientManager renewals;
    private final OAuth2AuthorizedClientRepository tokens;
    private final String registrationId;
    private final Duration maxWithoutVerdict;
    private final Duration retryDelay;
    private final Clock clock;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    /**
     * @param renewals Spring Security's manager, with the refresh grant only
     * @param tokens   where the session's tokens are kept — the same repository
     *                 the manager uses
     */
    public SessionRevalidationFilter(OAuth2AuthorizedClientManager renewals, OAuth2AuthorizedClientRepository tokens,
                                     String registrationId, SecurityProperties.Revalidation settings, Clock clock) {
        this.renewals = Objects.requireNonNull(renewals, "renewals");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.registrationId = Objects.requireNonNull(registrationId, "registration id");
        this.maxWithoutVerdict = settings.maxWithoutVerdict();
        this.retryDelay = settings.retryDelay();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (contexts.getContext().getAuthentication() instanceof OAuth2AuthenticationToken signedIn
                && !stillVouchedFor(signedIn, request, response)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            contexts.clearContext();
        }
        chain.doFilter(request, response);
    }

    private boolean stillVouchedFor(OAuth2AuthenticationToken signedIn, HttpServletRequest request,
                                    HttpServletResponse response) {
        Instant now = clock.instant();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        if (session.getAttribute(NEXT_ATTEMPT) instanceof Instant next && now.isBefore(next)) {
            return true;
        }

        OAuth2AuthorizedClient current;
        try {
            // Nothing is sent while the access token is valid; past its expiry
            // (less one minute of clock skew), the refresh grant is.
            current = renewals.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                    .principal(signedIn)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build());
        } catch (OAuth2AuthorizationException failure) {
            String code = failure.getError().getErrorCode();
            if (OAuth2ErrorCodes.INVALID_GRANT.equals(code)) {
                LOG.info("Session of {} ended by Keycloak", signedIn.getName());
                return false;
            }
            return servedOnLastVerdict(signedIn, request, session, now, code);
        }

        if (current == null || expired(current.getAccessToken(), now)) {
            // No tokens at all, or no refresh token to renew them with.
            LOG.warn("Session of {} closed: nothing to revalidate it with (does the realm client issue "
                    + "refresh tokens?)", signedIn.getName());
            return false;
        }
        if (session.getAttribute(NEXT_ATTEMPT) != null) {
            session.removeAttribute(NEXT_ATTEMPT);
        }
        return true;
    }

    private boolean servedOnLastVerdict(OAuth2AuthenticationToken signedIn, HttpServletRequest request,
                                        HttpSession session, Instant now, String reason) {
        OAuth2AuthorizedClient last = tokens.loadAuthorizedClient(registrationId, signedIn, request);
        Instant lastVerdict = last == null ? null : last.getAccessToken().getIssuedAt();
        if (lastVerdict == null || !now.isBefore(lastVerdict.plus(maxWithoutVerdict))) {
            LOG.warn("Session of {} closed: no verdict from Keycloak for {} ({})", signedIn.getName(),
                    maxWithoutVerdict, reason);
            return false;
        }
        session.setAttribute(NEXT_ATTEMPT, now.plus(retryDelay));
        LOG.warn("Session of {} could not be revalidated ({}); served on its last verdict until {} at the latest",
                signedIn.getName(), reason, lastVerdict.plus(maxWithoutVerdict));
        return true;
    }

    private static boolean expired(OAuth2AccessToken token, Instant now) {
        return token.getExpiresAt() != null && !now.isBefore(token.getExpiresAt());
    }
}
