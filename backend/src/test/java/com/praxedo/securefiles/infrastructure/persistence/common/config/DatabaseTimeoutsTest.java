package com.praxedo.securefiles.infrastructure.persistence.common.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class DatabaseTimeoutsTest {

    @Test
    @DisplayName("zero means « no timeout » to the driver: refused")
    void zero_is_refused() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new DatabaseTimeouts(Duration.ZERO, Duration.ofSeconds(15), Duration.ofSeconds(30)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new DatabaseTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofMillis(500)));
    }

    @Test
    @DisplayName("the server cancels a statement before the driver drops the connection")
    void the_socket_timeout_exceeds_the_statement_timeout() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new DatabaseTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30)));
        assertThatNoException().isThrownBy(
                () -> new DatabaseTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30)));
    }
}
