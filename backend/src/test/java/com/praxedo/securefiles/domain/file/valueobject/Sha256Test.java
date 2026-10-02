package com.praxedo.securefiles.domain.file.valueobject;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class Sha256Test {

    private static final String VALID = "e".repeat(64);

    @Test
    @DisplayName("case is normalised: engines disagree, comparisons must not")
    void uppercase_is_accepted_and_stored_lowercase() {
        assertThat(Sha256.of("E".repeat(64)).value()).isEqualTo(VALID);
        assertThat(Sha256.of("E".repeat(64))).isEqualTo(new Sha256(VALID));
    }

    @ParameterizedTest
    @MethodSource("notDigests")
    void anything_that_is_not_a_digest_is_refused(String candidate) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new Sha256(candidate));
    }

    static Stream<String> notDigests() {
        return Stream.of(
                "",                    // empty
                "abc",                 // too short
                "g".repeat(64),        // not hexadecimal
                "e".repeat(63),        // one character short
                "e".repeat(65),        // one character too many
                " " + "e".repeat(63),  // padded
                "E".repeat(64));       // uppercase must go through of()
    }

    @Test
    void two_digests_of_the_same_content_are_equal() {
        assertThat(Sha256.of(VALID)).isEqualTo(Sha256.of(VALID));
        assertThat(Sha256.of(VALID)).isNotEqualTo(Sha256.of("f".repeat(64)));
    }
}
