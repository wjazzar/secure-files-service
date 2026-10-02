package com.praxedo.securefiles.infrastructure.web.common.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Who is asking — the single place where that is decided.
 *
 * <p>The owner is the subject Keycloak vouched for — in the identity token of
 * the browser's session (ADR-0012) or in a third-party system's access token
 * (ADR-0014). Both carry the same {@code sub}, so a user's files are the same
 * whichever way they arrive. There is no fallback owner: a request without a
 * verified credential has no owner, and no file space to reach (ADR-0014).
 */
@Component
public class CurrentOwner {

    /**
     * Never null.
     *
     * @throws IllegalStateException without a verified credential — the filter
     *         chains make it unreachable; if it is ever reached, any owner made
     *         up here would silently merge the files of everyone who reached
     *         it, so it fails instead
     */
    public OwnerId resolve() {
        return user().owner();
    }

    /** The owner, with what the interface displays about them. Same guarantees as {@link #resolve()}. */
    public CurrentUser user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken session
                && session.getPrincipal() instanceof OidcUser user) {
            return new CurrentUser(new OwnerId(user.getSubject()), user.getPreferredUsername(), user.getFullName(),
                    user.getEmail());
        }
        if (authentication instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            return new CurrentUser(new OwnerId(jwt.getSubject()), jwt.getClaimAsString("preferred_username"),
                    jwt.getClaimAsString("name"), jwt.getClaimAsString("email"));
        }
        throw new IllegalStateException("No verified credential for a request that requires one");
    }
}
