package com.praxedo.securefiles.infrastructure.web.common.error;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * The contract's {@code 403}: a problem document with the stable code
 * {@code CSRF_TOKEN_INVALID}.
 *
 * <p>The contract has no roles, so nothing else in the service is ever
 * forbidden: a file of someone else is {@code 404}. The one refusal left is a
 * write authenticated by the session cookie that does not echo the CSRF token
 * — the signature of a request forged by another site, or of a client that
 * forgot the header.
 */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper json;

    public ProblemAccessDeniedHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException denied) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Forbidden");
        problem.put("status", HttpServletResponse.SC_FORBIDDEN);
        problem.put("detail", "A write sent with the session cookie must echo the XSRF-TOKEN cookie in X-XSRF-TOKEN.");
        problem.put("instance", request.getRequestURI());
        problem.put("code", ErrorCode.CSRF_TOKEN_INVALID.name());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
