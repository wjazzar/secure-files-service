package com.praxedo.securefiles.infrastructure.persistence.file;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.praxedo.securefiles.testsupport.DatabaseFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The last line of defence, tested where it lives: in the database.
 *
 * <p>This is the test that justifies writing the constraints with
 * {@code IS NOT DISTINCT FROM} rather than {@code =}. A {@code CHECK} rejects
 * {@code FALSE} and <strong>accepts {@code UNKNOWN}</strong>, so a naive
 * {@code scan_result = 'CLEAN'} lets a row through as soon as
 * {@code scan_result} is {@code NULL} — exactly the row the constraint claims
 * to forbid.
 *
 * <p>Each case below nulls out <em>one</em> field of an otherwise valid
 * {@code AVAILABLE} row. All of them must be refused. The first test checks
 * that the untouched row <em>is</em> accepted: without it, this suite would
 * still pass if every insert failed for some unrelated reason.
 */
class StoredFileConstraintsTest extends DatabaseFixture {

    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);

    @Test
    @DisplayName("the reference row is accepted — without this, the suite would prove nothing")
    void a_complete_available_row_is_accepted() {
        assertThatNoException().isThrownBy(() -> insert(availableRow()));

        assertThat(count("status = 'AVAILABLE'")).isEqualTo(1);
    }

    @ParameterizedTest
    @DisplayName("an AVAILABLE row is refused as soon as one piece of the attestation is missing")
    @ValueSource(strings = {
            "scan_result",
            "scan_engine",
            "scan_signature_version",
            "scanned_sha256",
            "scanned_at"})
    void available_without_a_complete_attestation_is_refused(String missingColumn) {
        Map<String, Object> row = availableRow();
        row.put(missingColumn, null);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));

        assertThat(count("true")).isZero();
    }

    @Test
    @DisplayName("an attestation about other bytes does not make a file available")
    void available_with_an_attestation_for_other_content_is_refused() {
        Map<String, Object> row = availableRow();
        row.put("scanned_sha256", OTHER_DIGEST);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    @DisplayName("an available file that never left quarantine is refused")
    void available_outside_the_servable_area_is_refused() {
        Map<String, Object> row = availableRow();
        row.put("storage_area", "QUARANTINE");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    @DisplayName("nothing but an available file lives in the servable area")
    void a_pending_file_cannot_sit_in_the_servable_area() {
        Map<String, Object> row = quarantinedRow();
        row.put("storage_area", "SERVABLE");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    void an_infected_status_without_the_matching_verdict_is_refused() {
        Map<String, Object> row = quarantinedRow();
        row.put("status", "INFECTED");
        row.put("scan_result", "CLEAN");
        row.put("scanned_at", OffsetDateTime.now());

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    void a_verdict_without_a_date_is_refused() {
        Map<String, Object> row = quarantinedRow();
        row.put("scan_result", "CLEAN");   // scanned_at left null

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    @DisplayName("a lease exists if and only if work is in progress — both directions")
    void the_lease_and_the_status_cannot_disagree() {
        Map<String, Object> waitingWithLease = quarantinedRow();
        waitingWithLease.put("lease_token", UUID.randomUUID());
        waitingWithLease.put("lease_holder", "worker-1");
        waitingWithLease.put("lease_expires_at", OffsetDateTime.now().plusMinutes(10));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(waitingWithLease));

        Map<String, Object> scanningWithoutLease = quarantinedRow();
        scanningWithoutLease.put("status", "SCANNING");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(scanningWithoutLease));
    }

    @Test
    void a_half_written_lease_is_refused() {
        Map<String, Object> row = quarantinedRow();
        row.put("status", "SCANNING");
        row.put("lease_token", UUID.randomUUID());
        row.put("lease_holder", "worker-1");
        // lease_expires_at deliberately left null

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    void an_empty_file_is_refused_by_the_database_too() {
        Map<String, Object> row = quarantinedRow();
        row.put("size_bytes", 0L);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    @DisplayName("a digest that is not one is refused")
    void a_malformed_digest_is_refused() {
        Map<String, Object> row = quarantinedRow();
        row.put("content_sha256", "not-a-digest" + "0".repeat(52));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row));
    }

    @Test
    @DisplayName("two files cannot share a storage key — a convention is not a constraint")
    void the_object_key_is_unique() {
        Map<String, Object> first = quarantinedRow();
        insert(first);

        Map<String, Object> second = quarantinedRow();
        second.put("object_key", first.get("object_key"));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(second));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> quarantinedRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        UUID id = UUID.randomUUID();
        row.put("id", id);
        row.put("owner_id", "alice");
        row.put("original_filename", "rapport.pdf");
        row.put("detected_content_type", "application/pdf");
        row.put("size_bytes", 1_024L);
        row.put("content_sha256", DIGEST);
        row.put("storage_area", "QUARANTINE");
        row.put("object_key", id.toString());
        row.put("status", "AWAITING_SCAN");
        row.put("status_reason", null);
        row.put("scan_result", null);
        row.put("scan_engine", null);
        row.put("scan_signature_version", null);
        row.put("scanned_sha256", null);
        row.put("scanned_at", null);
        row.put("attempts", 0);
        row.put("lease_token", null);
        row.put("lease_holder", null);
        row.put("lease_expires_at", null);
        return row;
    }

    private Map<String, Object> availableRow() {
        Map<String, Object> row = quarantinedRow();
        row.put("storage_area", "SERVABLE");
        row.put("status", "AVAILABLE");
        row.put("scan_result", "CLEAN");
        row.put("scan_engine", "ClamAV");
        row.put("scan_signature_version", "28098");
        row.put("scanned_sha256", DIGEST);
        row.put("scanned_at", OffsetDateTime.now());
        row.put("attempts", 1);
        return row;
    }

    /** Enum columns need an explicit cast: JDBC sends them as text. */
    private static final Map<String, String> ENUM_COLUMNS = Map.of(
            "storage_area", "storage_area",
            "status", "file_status",
            "scan_result", "scan_result");

    private void insert(Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String values = row.keySet().stream()
                .map(column -> ENUM_COLUMNS.containsKey(column)
                        ? "CAST(:" + column + " AS " + ENUM_COLUMNS.get(column) + ")"
                        : ":" + column)
                .collect(Collectors.joining(", "));

        JdbcClient.StatementSpec statement =
                jdbc.sql("INSERT INTO stored_file (" + columns + ") VALUES (" + values + ")");
        for (Map.Entry<String, Object> parameter : row.entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        statement.update();
    }

    private int count(String predicate) {
        return jdbc.sql("SELECT count(*) FROM stored_file WHERE " + predicate)
                .query(Integer.class)
                .single();
    }
}
