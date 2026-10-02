package com.praxedo.securefiles.testsupport;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The one PostgreSQL the whole test suite shares.
 *
 * <p>Started once for the JVM and reclaimed at exit, instead of once per test
 * class. The Spring tests reach it through {@link PostgresTestcontainer}, the
 * plain-SQL tests through {@link DatabaseFixture} — both point at this same
 * instance, so the suite costs one container start.
 *
 * <p>Same image as {@code docker-compose.yml}: the tests and the demonstration
 * run against the same engine. No in-memory database is used anywhere in this
 * project, because the guarantees under test — {@code FOR UPDATE SKIP LOCKED},
 * partial indexes, total {@code CHECK} predicates — are PostgreSQL behaviour.
 *
 * <p><strong>Same roles as {@code docker-compose.yml}</strong>, created by the
 * same script ({@code infra/postgres/initdb}): Flyway migrates as the schema
 * owner, the service runs as {@link #APP}, which can read and write rows and
 * nothing else. The whole suite thus proves that the service works without a
 * single privilege on the schema itself.
 */
public final class PostgresContainer {

    /** Owns the schema; only Flyway uses it. */
    public static final String OWNER = "praxedo_owner";
    public static final String OWNER_PASSWORD = "owner-test-password";

    /** The service at run time: rows only. */
    public static final String APP = "praxedo_app";
    public static final String APP_PASSWORD = "app-test-password";

    /** Keycloak's own role, owner of its own database and of nothing else. */
    public static final String KEYCLOAK = "keycloak";
    public static final String KEYCLOAK_PASSWORD = "keycloak-test-password";

    /**
     * {@code postgres:17.11-alpine}, pinned by digest as in
     * {@code docker-compose.yml}. Digest alone: Testcontainers does not accept
     * a tag and a digest together.
     */
    private static final String IMAGE =
            "postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24";

    /**
     * Spring keeps up to 32 test contexts alive, each holding two pools — the
     * API's and the work queue's, 16 connections in all. PostgreSQL's default
     * of 100 clients runs out halfway through the suite.
     */
    public static final PostgreSQLContainer INSTANCE = new PostgreSQLContainer(IMAGE)
            .withCommand("postgres", "-c", "max_connections=500")
            .withEnv("DB_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("DB_APP_PASSWORD", APP_PASSWORD)
            .withEnv("KEYCLOAK_DB_PASSWORD", KEYCLOAK_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "postgres", "initdb", "01-roles-and-databases.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-roles-and-databases.sh");

    static {
        INSTANCE.start();
    }

    private PostgresContainer() {
    }

    /** A connection as the schema owner: migrations, and the tests' own set-up and clean-up. */
    public static DataSource ownerDataSource() {
        return dataSource(INSTANCE.getDatabaseName(), OWNER, OWNER_PASSWORD);
    }

    /** A connection as the service sees the database. */
    public static DataSource appDataSource() {
        return dataSource(INSTANCE.getDatabaseName(), APP, APP_PASSWORD);
    }

    /** A connection to any database of the instance, as any role. */
    public static DataSource dataSource(String database, String user, String password) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl("jdbc:postgresql://" + INSTANCE.getHost() + ":"
                + INSTANCE.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        return dataSource;
    }
}
