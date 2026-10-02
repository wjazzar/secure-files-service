package com.praxedo.securefiles.infrastructure.persistence.idempotency.adapter;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.praxedo.securefiles.application.idempotency.port.out.IdempotencyStore;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Idempotency keys, settled by the primary key of their table.
 *
 * <p>{@link #reserve} is an {@code INSERT … ON CONFLICT DO NOTHING}: the
 * database decides which of two concurrent retries owns the key, atomically.
 * Only the loser reads what is there — never the other way round, which would
 * let both see "nothing yet".
 *
 * <p>It is plain SQL rather than JPA for the same reason as the work queue:
 * the operation that matters is a conditional write whose outcome is the
 * answer, and there is no entity to manage.
 */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    /** A reservation is retried at most this often when it races a release or an expiry. */
    private static final int MAX_ROUNDS = 3;

    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Reservation reserve(OwnerId owner, String key, String fingerprint, Duration ttl) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int inserted = jdbc.sql("""
                    INSERT INTO idempotency_record (owner_id, idempotency_key, request_fingerprint, state, expires_at)
                    VALUES (:owner, :key, :fingerprint, 'IN_PROGRESS',
                            clock_timestamp() + make_interval(secs => :ttlSeconds))
                    ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                    """)
                    .param("owner", owner.value())
                    .param("key", key)
                    .param("fingerprint", fingerprint)
                    .param("ttlSeconds", (double) ttl.toSeconds())
                    .update();
            if (inserted == 1) {
                return new Reservation.Granted();
            }

            Optional<Existing> existing = find(owner, key);
            if (existing.isEmpty()) {
                continue;   // released between our insert and our read: try again
            }
            Existing record = existing.get();
            if (record.expired()) {
                // An expired key is a free key. The delete is conditional, so two
                // racers cannot both remove a key someone just re-reserved.
                jdbc.sql("""
                        DELETE FROM idempotency_record
                         WHERE owner_id = :owner AND idempotency_key = :key
                           AND expires_at < clock_timestamp()
                        """)
                        .param("owner", owner.value()).param("key", key).update();
                continue;
            }
            if (!record.fingerprint().equals(fingerprint)) {
                return new Reservation.Reused();
            }
            if (record.fileId() == null) {
                return new Reservation.InProgress();
            }
            return new Reservation.Replay(new FileId(record.fileId()));
        }
        // Three rounds of races on one key: the client is hammering it. Busy is honest.
        return new Reservation.InProgress();
    }

    @Override
    public void complete(OwnerId owner, String key, FileId file) {
        int updated = jdbc.sql("""
                UPDATE idempotency_record
                   SET state = 'COMPLETED', file_id = :file
                 WHERE owner_id = :owner AND idempotency_key = :key AND state = 'IN_PROGRESS'
                """)
                .param("file", file.value())
                .param("owner", owner.value())
                .param("key", key)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("An idempotency key was completed without being reserved");
        }
    }

    @Override
    public void release(OwnerId owner, String key) {
        jdbc.sql("""
                DELETE FROM idempotency_record
                 WHERE owner_id = :owner AND idempotency_key = :key AND state = 'IN_PROGRESS'
                """)
                .param("owner", owner.value())
                .param("key", key)
                .update();
    }

    @Override
    public int purge(Duration abandonedAfter) {
        return jdbc.sql("""
                DELETE FROM idempotency_record
                 WHERE expires_at < clock_timestamp()
                    OR (state = 'IN_PROGRESS'
                        AND created_at < clock_timestamp() - make_interval(secs => :abandonedSeconds))
                """)
                .param("abandonedSeconds", (double) abandonedAfter.toSeconds())
                .update();
    }

    private Optional<Existing> find(OwnerId owner, String key) {
        return jdbc.sql("""
                SELECT request_fingerprint, file_id, expires_at < clock_timestamp() AS expired
                  FROM idempotency_record
                 WHERE owner_id = :owner AND idempotency_key = :key
                """)
                .param("owner", owner.value())
                .param("key", key)
                .query((rs, row) -> new Existing(
                        rs.getString("request_fingerprint").trim(),
                        rs.getObject("file_id", UUID.class),
                        rs.getBoolean("expired")))
                .optional();
    }

    private record Existing(String fingerprint, UUID fileId, boolean expired) {
    }
}
