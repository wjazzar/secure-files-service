package com.praxedo.securefiles.infrastructure.persistence.file.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileStatusSqlTest {

    @Test
    @DisplayName("the sets the statements filter on are the automaton's")
    void the_sets_follow_the_automaton() {
        assertThat(FileStatusSql.CLAIMABLE).isEqualTo("'AWAITING_SCAN', 'RETRY_WAIT'");
        assertThat(FileStatusSql.LEASED).isEqualTo("'SCANNING', 'PROMOTING'");
        assertThat(FileStatusSql.PENDING).isEqualTo("'AWAITING_SCAN', 'SCANNING', 'RETRY_WAIT', 'PROMOTING'");
    }

    @Test
    @DisplayName("⭐ the partial indexes are written on the same sets: a change to the automaton needs a migration")
    void the_partial_indexes_match_the_sets() throws IOException {
        try (InputStream migration = getClass().getResourceAsStream("/db/migration/V3__indexes.sql")) {
            String indexes = new String(migration.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(indexes)
                    .contains("WHERE status IN (" + FileStatusSql.CLAIMABLE + ")")
                    .contains("WHERE status IN (" + FileStatusSql.LEASED + ")");
        }
    }
}
