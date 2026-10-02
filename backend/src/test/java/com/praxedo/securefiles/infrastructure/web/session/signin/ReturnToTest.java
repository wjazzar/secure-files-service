package com.praxedo.securefiles.infrastructure.web.session.signin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The sign-in must not become an open redirection towards a look-alike site. */
class ReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/files", "/files/0c0f?status=AVAILABLE&q=r%C3%A9sum%C3%A9", "/files#top"})
    @DisplayName("a path of this application is kept as it is")
    void local_paths_are_kept(String path) {
        assertThat(ReturnTo.sanitize(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://evil.test/files",
            "//evil.test/files",
            "/\\evil.test",
            "\\\\evil.test",
            "javascript:alert(1)",
            "files",
            "/files\r\nSet-Cookie: x=y",
            "/ /evil.test",
            "/\t/evil.test"})
    @DisplayName("⭐ anything else goes home")
    void anything_else_goes_home(String target) {
        assertThat(ReturnTo.sanitize(target)).isEqualTo("/");
    }

    @Test
    @DisplayName("an absurdly long target goes home too")
    void long_targets_go_home() {
        assertThat(ReturnTo.sanitize("/" + "a".repeat(5_000))).isEqualTo("/");
    }
}
