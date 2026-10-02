package com.praxedo.securefiles.infrastructure.persistence.common.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the service waits for PostgreSQL, on both pools — rule B-7: every
 * outgoing call has an explicit connect <em>and</em> read timeout.
 *
 * <p>The pool's own {@code connection-timeout} only bounds the wait for a free
 * connection. Without these, a database that stops answering — a frozen disk,
 * a network partition — holds the waiting thread for ever: the driver's socket
 * timeout is infinite by default, and no statement timeout is set.
 *
 * <p>No statement of the service legitimately runs long: each is a lookup, a
 * conditional update or a short page. Migrations are not concerned: Flyway
 * connects on its own, as the schema owner.
 *
 * @param connect   to open a connection
 * @param statement after which PostgreSQL itself cancels a statement
 * @param socket    after which the driver gives up on a silent server; longer
 *                  than {@code statement}, so that the server's cancellation,
 *                  which leaves the connection usable, comes first
 */
@ConfigurationProperties("praxedo.database")
public record DatabaseTimeouts(Duration connect, Duration statement, Duration socket) {

    public DatabaseTimeouts {
        Objects.requireNonNull(connect, "praxedo.database.connect");
        Objects.requireNonNull(statement, "praxedo.database.statement");
        Objects.requireNonNull(socket, "praxedo.database.socket");
        if (connect.toSeconds() < 1 || statement.toMillis() < 1 || socket.toSeconds() < 1) {
            throw new IllegalArgumentException("Database timeouts must be explicit and positive: 0 means none");
        }
        if (socket.compareTo(statement) <= 0) {
            throw new IllegalArgumentException("The socket timeout must exceed the statement timeout");
        }
    }
}
