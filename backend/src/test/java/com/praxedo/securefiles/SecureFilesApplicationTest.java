package com.praxedo.securefiles;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.testsupport.PostgresTestcontainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test of the composition root against a real PostgreSQL.
 *
 * <p>It proves three things at once, which is why it is worth its start-up
 * cost: the context wires up with no profile to remember, Flyway actually runs
 * its migrations, and the extension the search index needs is installed.
 */
@SpringBootTest
class SecureFilesApplicationTest extends PostgresTestcontainer {

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("the application starts as a single process, with nothing to configure")
    void context_loads() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flyway_has_applied_its_migrations() {
        // Read as the owner: the service's own role has no access to the history.
        Integer applied = owner
                .sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class)
                .single();

        assertThat(applied).isPositive();
    }

    @Test
    void the_trigram_extension_is_available() {
        Integer installed = jdbc
                .sql("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'")
                .query(Integer.class)
                .single();

        assertThat(installed).isEqualTo(1);
    }
}
