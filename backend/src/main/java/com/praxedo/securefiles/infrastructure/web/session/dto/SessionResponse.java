package com.praxedo.securefiles.infrastructure.web.session.dto;

import com.praxedo.securefiles.infrastructure.web.common.identity.CurrentUser;

/**
 * The contract's {@code Session}: who is signed in, as the interface shows it.
 * No token, no expiry — the browser has nothing to do with either.
 *
 * @param id          the subject; the owner of the file space
 * @param email       {@code null} when the account has none
 */
public record SessionResponse(String id, String username, String displayName, String email) {

    public static SessionResponse from(CurrentUser user) {
        return new SessionResponse(user.owner().value(), user.username(), user.displayName(), user.email());
    }
}
