package com.praxedo.securefiles.testsupport;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A real PostgreSQL, migrated by the real Flyway scripts — and no Spring
 * context at all.
 *
 * <p>These tests target SQL, so they load nothing but SQL: the shared
 * container ({@link PostgresContainer}) is already running, the migrations run
 * once, and every test starts from empty tables. A persistence test costs
 * milliseconds rather than a context start.
 *
 * <p>Migrated and queried as the schema owner, as Flyway does in production:
 * these tests probe the schema's own guarantees, which must hold whoever
 * writes. What the service's role may or may not do is
 * {@code DatabaseRolesTest}'s subject.
 */
public abstract class DatabaseFixture {

    private static final DataSource DATA_SOURCE;

    static {
        DATA_SOURCE = PostgresContainer.ownerDataSource();

        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    protected final JdbcClient jdbc = JdbcClient.create(DATA_SOURCE);

    @BeforeEach
    void emptyTheTables() {
        jdbc.sql("TRUNCATE idempotency_record, stored_file, spring_session CASCADE").update();
    }
}
