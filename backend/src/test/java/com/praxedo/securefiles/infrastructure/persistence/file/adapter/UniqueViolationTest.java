package com.praxedo.securefiles.infrastructure.persistence.file.adapter;

import java.sql.SQLException;

import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Only a duplicate is reported as a duplicate: every other failure keeps its meaning. */
class UniqueViolationTest {

    @Test
    @DisplayName("unique_violation, however deep in the chain: a duplicate")
    void a_unique_violation_is_a_duplicate() {
        assertThat(JpaFileCatalog.isUniqueViolation(
                new PersistenceException(new RuntimeException(new SQLException("duplicate key", "23505"))))).isTrue();
    }

    @Test
    @DisplayName("a CHECK refusal or a lost connection is not a duplicate")
    void anything_else_is_not() {
        assertThat(JpaFileCatalog.isUniqueViolation(
                new PersistenceException(new SQLException("check", "23514")))).isFalse();
        assertThat(JpaFileCatalog.isUniqueViolation(
                new PersistenceException(new SQLException("connection lost", "08006")))).isFalse();
        assertThat(JpaFileCatalog.isUniqueViolation(new PersistenceException("no cause"))).isFalse();
    }
}
