package com.praxedo.securefiles.infrastructure.web.session.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.praxedo.securefiles.infrastructure.web.common.identity.CurrentOwner;
import com.praxedo.securefiles.infrastructure.web.session.dto.SessionResponse;

/**
 * Who is signed in. Signing in and out are not here: both are Spring
 * Security's — a browser navigation to {@code /api/v1/auth/login}, and
 * {@code POST /api/v1/auth/logout} (see {@code BrowserSessionConfigurer}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {

    private final CurrentOwner currentOwner;

    public SessionController(CurrentOwner currentOwner) {
        this.currentOwner = currentOwner;
    }

    /**
     * Who is signed in — {@code 401} otherwise, which is how the interface
     * learns it has to send the browser to the sign-in.
     *
     * <p>For a browser, reading the CSRF token is what makes Spring Security
     * issue the {@code XSRF-TOKEN} cookie: the interface calls this first, so the
     * cookie is there before its first write. A bearer-token call has no CSRF
     * token, and needs none.
     */
    @GetMapping("/session")
    ResponseEntity<SessionResponse> session(CsrfToken csrf) {
        if (csrf != null) {
            csrf.getToken();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SessionResponse.from(currentOwner.user()));
    }
}
