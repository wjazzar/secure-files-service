package com.praxedo.securefiles.infrastructure.persistence.file;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.testsupport.FileFixtures;
import com.praxedo.securefiles.testsupport.PostgresTestcontainer;
import com.praxedo.securefiles.testsupport.Leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The audit trail ({@code V6__audit.sql}): every state transition leaves an
 * event, written by the database in the same transaction — and nothing can
 * rewrite one.
 *
 * <p>The files are driven through the real queue statements, so what is
 * checked is what production writes, not what a test inserted.
 */
@SpringBootTest
class AuditTrailTest extends PostgresTestcontainer {

    @Autowired
    FileCatalog catalog;

    @Autowired
    FileWorkQueue queue;

    FileFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new FileFixtures(catalog, queue);
    }

    @Test
    @DisplayName("a clean file's life: uploaded by its owner, claimed, judged and promoted by the worker")
    void the_life_of_an_available_file() {
        StoredFile file = fixtures.available("rapport.pdf");

        assertThat(events(file)).containsExactly(
                "UPLOADED null→AWAITING_SCAN by " + FileFixtures.OWNER,
                "CLAIMED AWAITING_SCAN→SCANNING by fixture",
                "VERDICT SCANNING→PROMOTING by fixture",
                "PROMOTED PROMOTING→AVAILABLE by fixture");
    }

    @Test
    @DisplayName("a verdict records the threat and the signatures that found it")
    void a_verdict_records_its_evidence() {
        StoredFile file = fixtures.infected("facture.pdf");

        String details = jdbc.sql("""
                SELECT details::text FROM file_audit_event WHERE file_id = :id AND event_type = 'VERDICT'
                """).param("id", file.id().value()).query(String.class).single();

        assertThat(details).contains("\"threat\": \"Eicar-Test-Signature\"", "\"signatureVersion\": \"28098\"",
                "\"scanResult\": \"INFECTED\"");
    }

    @Test
    @DisplayName("a technical failure is recorded with its cause")
    void a_technical_failure_is_recorded() {
        StoredFile file = fixtures.retryWait("slow.bin");

        assertThat(events(file)).last().isEqualTo("TECHNICAL_FAILURE SCANNING→RETRY_WAIT by fixture");
    }

    @Test
    @DisplayName("a lease that expired without a conclusion is attributed to the reaper, not to the worker that vanished")
    void an_expired_lease_is_attributed_to_the_reaper() throws InterruptedException {
        StoredFile file = fixtures.awaitingScan("orphan.bin");
        queue.claimNextDue("vanished-worker", LeaseToken.random(), Leases.fixed(Duration.ofMillis(1)), 3).orElseThrow();
        Thread.sleep(50);

        queue.reclaimExpiredLeases(3, Duration.ofMinutes(1), Duration.ofMinutes(5));

        assertThat(events(file)).last().isEqualTo("LEASE_EXPIRED SCANNING→RETRY_WAIT by reaper");
    }

    /**
     * Run as the schema OWNER on purpose: the service's own role has no right to
     * rewrite the trail anyway (DatabaseRolesTest), so only the owner shows that
     * the triggers refuse whoever asks.
     */
    @Test
    @DisplayName("⭐ append-only: no update, no delete, no truncate — refused by the database itself, even to the schema owner")
    void the_trail_cannot_be_rewritten() {
        StoredFile file = fixtures.awaitingScan("evidence.bin");
        UUID id = file.id().value();

        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> owner.sql(
                "UPDATE file_audit_event SET actor = 'nobody' WHERE file_id = :id").param("id", id).update())
                .havingRootCause().withMessageContaining("append-only");
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> owner.sql(
                "DELETE FROM file_audit_event WHERE file_id = :id").param("id", id).update())
                .havingRootCause().withMessageContaining("append-only");
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> owner.sql(
                "TRUNCATE file_audit_event").update())
                .havingRootCause().withMessageContaining("append-only");

        assertThat(events(file)).containsExactly("UPLOADED null→AWAITING_SCAN by " + FileFixtures.OWNER);
    }

    @Test
    @DisplayName("the trail outlives the file it describes")
    void the_trail_outlives_the_file() {
        StoredFile file = fixtures.awaitingScan("purged.bin");

        owner.sql("TRUNCATE idempotency_record, stored_file CASCADE").update();

        assertThat(events(file)).containsExactly("UPLOADED null→AWAITING_SCAN by " + FileFixtures.OWNER);
    }

    private List<String> events(StoredFile file) {
        return jdbc.sql("""
                SELECT event_type || ' ' || coalesce(from_status::text, 'null') || '→' || to_status::text
                       || ' by ' || actor
                  FROM file_audit_event
                 WHERE file_id = :id
                 ORDER BY id
                """).param("id", file.id().value()).query(String.class).list();
    }
}
