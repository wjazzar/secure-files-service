package com.praxedo.securefiles.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for the tests that start a Spring context: wired to the shared
 * container, and every test starts from empty tables.
 *
 * <p><strong>Two roles, as in production.</strong> Flyway migrates as the
 * schema owner; the service runs as {@code praxedo_app}, which reads and writes
 * rows and nothing else. {@link #jdbc} is the service's own connection;
 * {@link #owner} is for what only a test does — emptying tables.
 *
 * <p><strong>No credential comes from {@code application.yml}</strong>, which
 * has none: each is given here, as a deployment would.
 *
 * <p><strong>Background work is off.</strong> The scheduled chores and the
 * analysis workers would otherwise race the situation a test has just set up —
 * reclaiming a lease the test expired on purpose, or claiming a file the test
 * meant to drive by hand. The tests that exercise them switch them on
 * explicitly.
 *
 * <p><strong>Authentication is on, as it always is.</strong> The service is
 * wired to a simulated Keycloak ({@link TestIdentityProvider}); what these
 * tests exercise — the files, their states, their storage — is the same
 * whoever the owner is, so they act as {@link TestIdentityProvider#USER}.
 *
 * <p>Tests that only exercise SQL do not need a context — they extend
 * {@link DatabaseFixture} instead, and start nothing.
 */
@TestPropertySource(properties = {
        "praxedo.scheduling.enabled=false",
        "praxedo.worker.enabled=false"
})
public abstract class PostgresTestcontainer {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainer.APP);
        registry.add("spring.datasource.password", () -> PostgresContainer.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresContainer.OWNER);
        registry.add("spring.flyway.password", () -> PostgresContainer.OWNER_PASSWORD);
    }

    @DynamicPropertySource
    static void objectStorageIdentities(DynamicPropertyRegistry registry) {
        StorageIdentities.register(registry);
    }

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        TestIdentityProvider.register(registry);
    }

    /** The service's own connection: what it may do, the test may do. */
    @Autowired
    protected JdbcClient jdbc;

    /** The schema owner's connection, for what only a test does. */
    protected final JdbcClient owner = JdbcClient.create(PostgresContainer.ownerDataSource());

    /** Named apart from subclasses' own set-up: JUnit would otherwise run only theirs. */
    @BeforeEach
    void startFromEmptyTables() {
        owner.sql("TRUNCATE idempotency_record, stored_file, spring_session CASCADE").update();
    }
}
