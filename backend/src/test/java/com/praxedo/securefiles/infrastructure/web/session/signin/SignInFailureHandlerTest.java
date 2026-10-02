package com.praxedo.securefiles.infrastructure.web.session.signin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

class SignInFailureHandlerTest {

    @Test
    @DisplayName("a real error code is logged as it is: the operator learns which check failed")
    void a_real_code_is_kept() {
        assertThat(SignInFailureHandler.loggable("invalid_state_parameter")).isEqualTo("invalid_state_parameter");
        assertThat(SignInFailureHandler.loggable("access_denied")).isEqualTo("access_denied");
    }

    @Test
    @DisplayName("a code chosen by the caller cannot forge a log line")
    void a_forged_code_is_not_logged() {
        assertThat(SignInFailureHandler.loggable("x\r\n2026-10-01 WARN Sign-in succeeded: admin"))
                .isEqualTo(SignInFailureHandler.UNRECOGNISED);
        assertThat(SignInFailureHandler.loggable("a".repeat(65))).isEqualTo(SignInFailureHandler.UNRECOGNISED);
        assertThat(SignInFailureHandler.loggable(null)).isEqualTo(SignInFailureHandler.UNRECOGNISED);
    }

    @Test
    @DisplayName("whatever the code, the browser only learns that the sign-in failed")
    void the_browser_gets_a_fixed_code() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SignInFailureHandler().onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("x\r\nforged")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=" + SignInFailureHandler.FAILED);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
