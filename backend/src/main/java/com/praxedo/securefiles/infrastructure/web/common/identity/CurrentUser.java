package com.praxedo.securefiles.infrastructure.web.common.identity;

import java.util.Objects;

import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Who is asking, as Keycloak vouched for it — through a browser session or an
 * access token.
 *
 * <p>Only {@link #owner()} decides anything: it is the {@code sub} Keycloak
 * signed, and it scopes every file query. The other fields are what the
 * interface displays; missing ones fall back on the username, then on the
 * subject, so that a sparse account still signs in.
 *
 * @param email {@code null} when the account has none
 */
public record CurrentUser(OwnerId owner, String username, String displayName, String email) {

    public CurrentUser {
        Objects.requireNonNull(owner, "owner");
        username = text(username, owner.value());
        displayName = text(displayName, username);
        email = email == null || email.isBlank() ? null : email;
    }

    private static String text(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}
