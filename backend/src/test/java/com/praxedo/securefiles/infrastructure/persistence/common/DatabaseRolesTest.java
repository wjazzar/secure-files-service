package com.praxedo.securefiles.infrastructure.persistence.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.praxedo.securefiles.testsupport.DatabaseFixture;
import com.praxedo.securefiles.testsupport.PostgresContainer;

/**
 * The database roles (audit S-03, {@code V8__database_roles.sql},
 * {@code infra/postgres/initdb}): the service runs as {@code praxedo_app},
 * which reads and writes rows — and nothing else.
 *
 * <p>This is what makes "structurally impossible to violate" true of the
 * schema: a flaw in the service, whatever it is, cannot remove a {@code CHECK}
 * constraint, switch off the audit trigger or rewrite the trail, because the
 * role it holds has no such right. The rest of the suite proves the converse:
 * every Spring test runs as this same role, so the service needs nothing more.
 */
@DisplayName("Database roles: the service's role reads and writes rows, and nothing else")
class DatabaseRolesTest extends DatabaseFixture {

    private final JdbcClient app = JdbcClient.create(PostgresContainer.appDataSource());

    @Test
    @DisplayName("⭐ cannot remove a CHECK constraint that carries the invariant")
    void cannot_drop_a_constraint() {
        assertThatThrownBy(() -> app.sql(
                "ALTER TABLE stored_file DROP CONSTRAINT available_requires_attestation").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("must be owner of table stored_file");

        assertThat(constraintExists("available_requires_attestation")).isTrue();
    }

    @Test
    @DisplayName("⭐ cannot switch off or drop the audit triggers")
    void cannot_touch_the_audit_triggers() {
        assertThatThrownBy(() -> app.sql(
                "ALTER TABLE file_audit_event DISABLE TRIGGER file_audit_event_no_rewrite").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("must be owner");
        assertThatThrownBy(() -> app.sql(
                "DROP TRIGGER stored_file_audit_transition ON stored_file").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("must be owner");
    }

    @Test
    @DisplayName("cannot rewrite the audit trail: no right to update, delete or truncate it")
    void cannot_rewrite_the_trail() {
        assertThatThrownBy(() -> app.sql("UPDATE file_audit_event SET actor = 'nobody'").update())
                .rootCause().hasMessageContaining("permission denied for table file_audit_event");
        assertThatThrownBy(() -> app.sql("DELETE FROM file_audit_event").update())
                .rootCause().hasMessageContaining("permission denied for table file_audit_event");
        assertThatThrownBy(() -> app.sql("TRUNCATE file_audit_event").update())
                .rootCause().hasMessageContaining("permission denied for table file_audit_event");
    }

    @Test
    @DisplayName("cannot change the schema: no new table, no truncate of the files")
    void cannot_change_the_schema() {
        assertThatThrownBy(() -> app.sql("CREATE TABLE intruder (id int)").update())
                .rootCause().hasMessageContaining("permission denied for schema public");
        assertThatThrownBy(() -> app.sql("TRUNCATE stored_file").update())
                .rootCause().hasMessageContaining("permission denied for table stored_file");
    }

    @Test
    @DisplayName("holds exactly the row privileges the service needs")
    void holds_the_row_privileges_it_needs() {
        assertThat(privilege("stored_file", "SELECT, INSERT, UPDATE, DELETE")).isTrue();
        assertThat(privilege("idempotency_record", "SELECT, INSERT, UPDATE, DELETE")).isTrue();
        assertThat(privilege("spring_session", "SELECT, INSERT, UPDATE, DELETE")).isTrue();
        assertThat(privilege("file_audit_event", "SELECT, INSERT")).isTrue();
        assertThat(privilege("flyway_schema_history", "SELECT")).isFalse();
    }

    @Test
    @DisplayName("⭐ the service and Keycloak cannot open each other's database")
    void the_databases_are_kept_apart() {
        assertThatThrownBy(() -> open(PostgresContainer.dataSource(
                "keycloak", PostgresContainer.APP, PostgresContainer.APP_PASSWORD)))
                .hasMessageContaining("permission denied for database \"keycloak\"");
        assertThatThrownBy(() -> open(PostgresContainer.dataSource(
                PostgresContainer.INSTANCE.getDatabaseName(), PostgresContainer.KEYCLOAK, PostgresContainer.KEYCLOAK_PASSWORD)))
                .hasMessageContaining("permission denied for database");
    }

    private boolean constraintExists(String name) {
        return jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conname = :name")
                .param("name", name).query(Long.class).single() == 1;
    }

    private boolean privilege(String table, String privileges) {
        return app.sql("SELECT has_table_privilege(current_user, :table, :privileges)")
                .param("table", table).param("privileges", privileges).query(Boolean.class).single();
    }

    private static void open(DataSource dataSource) throws Exception {
        try (Connection ignored = dataSource.getConnection()) {
            // The connection itself is what is refused.
        }
    }
}
