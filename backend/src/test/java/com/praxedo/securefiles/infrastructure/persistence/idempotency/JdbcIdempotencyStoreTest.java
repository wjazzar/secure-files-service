package com.praxedo.securefiles.infrastructure.persistence.idempotency;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.idempotency.port.out.IdempotencyStore.Reservation;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.infrastructure.persistence.idempotency.adapter.JdbcIdempotencyStore;
import com.praxedo.securefiles.testsupport.DatabaseFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Idempotency keys against the real database, where the race they exist to
 * settle actually happens.
 */
class JdbcIdempotencyStoreTest extends DatabaseFixture {

    private static final OwnerId OWNER = new OwnerId("carol");
    private static final String KEY = "key-12345678";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Duration TTL = Duration.ofHours(24);

    private JdbcIdempotencyStore store;

    @BeforeEach
    void store() {
        store = new JdbcIdempotencyStore(jdbc);
    }

    @Test
    @DisplayName("the first request gets the key, and once completed it replays its file")
    void a_completed_key_replays_its_file() {
        FileId file = insertFile();

        assertThat(store.reserve(OWNER, KEY, FINGERPRINT, TTL)).isInstanceOf(Reservation.Granted.class);
        store.complete(OWNER, KEY, file);

        assertThat(store.reserve(OWNER, KEY, FINGERPRINT, TTL)).isEqualTo(new Reservation.Replay(file));
    }

    @Test
    void a_key_still_streaming_is_reported_in_progress() {
        store.reserve(OWNER, KEY, FINGERPRINT, TTL);

        assertThat(store.reserve(OWNER, KEY, FINGERPRINT, TTL)).isInstanceOf(Reservation.InProgress.class);
    }

    @Test
    @DisplayName("the same key for a different request is a client bug")
    void a_key_reused_for_another_request_is_refused() {
        store.reserve(OWNER, KEY, FINGERPRINT, TTL);

        assertThat(store.reserve(OWNER, KEY, "b".repeat(64), TTL)).isInstanceOf(Reservation.Reused.class);
    }

    @Test
    @DisplayName("a released key is immediately usable again: the client's retry must succeed")
    void a_released_key_can_be_reserved_again() {
        store.reserve(OWNER, KEY, FINGERPRINT, TTL);
        store.release(OWNER, KEY);

        assertThat(store.reserve(OWNER, KEY, FINGERPRINT, TTL)).isInstanceOf(Reservation.Granted.class);
    }

    @Test
    void an_expired_key_is_a_free_key() {
        FileId file = insertFile();
        store.reserve(OWNER, KEY, FINGERPRINT, TTL);
        store.complete(OWNER, KEY, file);
        expire(KEY);

        assertThat(store.reserve(OWNER, KEY, "c".repeat(64), TTL)).isInstanceOf(Reservation.Granted.class);
    }

    @Test
    @DisplayName("keys are scoped to their owner")
    void two_owners_may_use_the_same_key() {
        store.reserve(new OwnerId("alice"), KEY, FINGERPRINT, TTL);

        assertThat(store.reserve(new OwnerId("bob"), KEY, FINGERPRINT, TTL)).isInstanceOf(Reservation.Granted.class);
    }

    @Test
    @DisplayName("eight concurrent retries with the same key: exactly one is granted")
    void concurrent_reservations_grant_the_key_once() throws Exception {
        int contenders = 8;
        List<Reservation> outcomes;
        try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
            List<Callable<Reservation>> attempts = IntStream.range(0, contenders)
                    .<Callable<Reservation>>mapToObj(index -> () -> store.reserve(OWNER, KEY, FINGERPRINT, TTL))
                    .toList();
            outcomes = pool.invokeAll(attempts).stream().map(JdbcIdempotencyStoreTest::get).toList();
        }

        assertThat(outcomes).filteredOn(Reservation.Granted.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(Reservation.InProgress.class::isInstance).hasSize(contenders - 1);
    }

    @Test
    void a_key_cannot_be_completed_without_being_reserved() {
        FileId file = insertFile();

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> store.complete(OWNER, KEY, file));
    }

    @Test
    @DisplayName("the purge forgets expired keys and reservations abandoned by a dead node")
    void the_purge_removes_expired_and_abandoned_keys() {
        FileId file = insertFile();
        store.reserve(OWNER, "expired-key-1", FINGERPRINT, TTL);
        store.complete(OWNER, "expired-key-1", file);
        expire("expired-key-1");
        store.reserve(OWNER, "abandoned-key", FINGERPRINT, TTL);
        jdbc.sql("UPDATE idempotency_record SET created_at = clock_timestamp() - interval '2 hours' "
                + "WHERE idempotency_key = 'abandoned-key'").update();
        store.reserve(OWNER, "live-key-12", FINGERPRINT, TTL);

        int purged = store.purge(Duration.ofHours(1));

        assertThat(purged).isEqualTo(2);
        assertThat(jdbc.sql("SELECT idempotency_key FROM idempotency_record").query(String.class).list())
                .containsExactly("live-key-12");
    }

    private FileId insertFile() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO stored_file (id, owner_id, original_filename, size_bytes, content_sha256, object_key)
                VALUES (:id, 'carol', 'a.bin', 10, :sha, :key)
                """)
                .param("id", id)
                .param("sha", "f".repeat(64))
                .param("key", id.toString())
                .update();
        return new FileId(id);
    }

    private void expire(String key) {
        jdbc.sql("UPDATE idempotency_record SET expires_at = clock_timestamp() - interval '1 second' "
                + "WHERE idempotency_key = :key").param("key", key).update();
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
