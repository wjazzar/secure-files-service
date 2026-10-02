package com.praxedo.securefiles.infrastructure.web.session.dto;

/**
 * The contract's {@code Logout}: where the browser goes next, once its session
 * here is closed.
 *
 * @param logoutUrl Keycloak's end-session address — which ends the session
 *                  there and sends the browser back to the login page — or,
 *                  without a session to end, the login page itself
 */
public record LogoutResponse(String logoutUrl) {
}
