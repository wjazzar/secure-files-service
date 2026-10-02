package com.praxedo.securefiles.infrastructure.web.common.error;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import tools.jackson.databind.json.JsonMapper;

/**
 * The contract's {@code 401}: a problem document with the stable code
 * {@code UNAUTHENTICATED}, and the {@code WWW-Authenticate: Bearer} challenge
 * of RFC 6750.
 *
 * <p>The challenge is computed by Spring Security's own entry point — it knows
 * whether to add {@code error="invalid_token"} — and only the body is written
 * here. Like every other error of the API, it says what to do, not why the
 * credential failed: an expired session, an expired token and a forged one get
 * the same answer.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
    private final JsonMapper json;

    public ProblemAuthenticationEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException failure) throws IOException, ServletException {
        challenge.commence(request, response, failure);

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Unauthenticated");
        problem.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        problem.put("detail", "Authentication is required: sign in again, or send a valid access token.");
        problem.put("instance", request.getRequestURI());
        problem.put("code", ErrorCode.UNAUTHENTICATED.name());

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
