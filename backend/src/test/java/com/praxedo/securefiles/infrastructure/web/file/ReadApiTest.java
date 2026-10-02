package com.praxedo.securefiles.infrastructure.web.file;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.testsupport.FileFixtures;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.PostgresTestcontainer;
import com.praxedo.securefiles.testsupport.TestIdentityProvider;
import com.praxedo.securefiles.testsupport.Leases;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three read operations, over real HTTP, against a real database.
 *
 * <p>Each test states one property of the contract. They go through the network
 * rather than a mocked dispatcher because conditional requests and problem
 * documents are precisely where a mock is right and the real stack is not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadApiTest extends PostgresTestcontainer {

    @LocalServerPort
    int port;

    @Autowired
    FileCatalog catalog;

    @Autowired
    FileWorkQueue queue;

    HttpApi api;
    FileFixtures files;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port).withToken(TestIdentityProvider.accessToken());
        files = new FileFixtures(catalog, queue);
    }

    @Nested
    @DisplayName("GET /api/v1/files")
    class Listing {

        @Test
        @DisplayName("a page has the contract's shape: content, then page metadata")
        void the_page_has_the_contract_shape() {
            files.awaitingScan("a.bin");

            HttpApi.Response response = api.get("/api/v1/files");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("Cache-Control")).contains("no-cache");
            JsonNode page = response.json();
            assertThat(page.path("content")).hasSize(1);
            assertThat(page.path("page").path("size").asInt()).isEqualTo(20);
            assertThat(page.path("page").path("number").asInt()).isZero();
            assertThat(page.path("page").path("totalElements").asLong()).isEqualTo(1);
            assertThat(page.path("page").path("totalPages").asInt()).isEqualTo(1);

            JsonNode first = page.path("content").get(0);
            assertThat(first.path("filename").asString()).isEqualTo("a.bin");
            assertThat(first.path("status").asString()).isEqualTo("PENDING");
            assertThat(first.path("downloadable").asBoolean()).isFalse();
            assertThat(first.path("terminal").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("a sort outside the allow-list is refused before it reaches the database")
        void an_unknown_sort_is_refused() {
            HttpApi.Response response = api.get("/api/v1/files?sort=original_filename;drop%20table,asc");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.header("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/problem+json"));
            assertThat(response.code()).isEqualTo("INVALID_PARAMETER");
        }

        @Test
        @DisplayName("a page that would skip more than 10 000 files is refused; one that skips exactly 10 000 is served")
        void a_page_too_deep_is_refused() {
            HttpApi.Response tooDeep = api.get("/api/v1/files?page=101&size=100");
            assertThat(tooDeep.status()).isEqualTo(400);
            assertThat(tooDeep.code()).isEqualTo("INVALID_PARAMETER");
            // The cap applies first: 5000 is 100, so page 101 is too deep there as well.
            assertThat(api.get("/api/v1/files?page=101&size=5000").code()).isEqualTo("INVALID_PARAMETER");

            assertThat(api.get("/api/v1/files?page=100&size=100").status()).isEqualTo(200);
        }

        @Test
        void a_negative_page_or_an_empty_page_size_is_refused() {
            assertThat(api.get("/api/v1/files?page=-1").code()).isEqualTo("INVALID_PARAMETER");
            assertThat(api.get("/api/v1/files?size=0").code()).isEqualTo("INVALID_PARAMETER");
            assertThat(api.get("/api/v1/files?page=abc").code()).isEqualTo("INVALID_PARAMETER");
        }

        @Test
        @DisplayName("a page size above the cap is brought back to the cap, not refused")
        void the_page_size_is_capped() {
            HttpApi.Response response = api.get("/api/v1/files?size=5000");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().path("page").path("size").asInt()).isEqualTo(100);
        }

        @Test
        @DisplayName("filtering on PENDING returns both waiting states, never the internal names")
        void the_status_filter_speaks_public_statuses() {
            files.available("done.bin");
            files.retryWait("retried.bin");
            files.awaitingScan("fresh.bin");

            JsonNode page = api.get("/api/v1/files?status=PENDING").json();

            assertThat(names(page)).containsExactlyInAnyOrder("fresh.bin", "retried.bin");
            assertThat(api.get("/api/v1/files?status=RETRY_WAIT").code()).isEqualTo("INVALID_PARAMETER");
        }

        @Test
        void the_status_filter_can_be_repeated() {
            files.available("done.bin");
            files.infected("bad.bin");
            files.awaitingScan("fresh.bin");

            JsonNode page = api.get("/api/v1/files?status=AVAILABLE&status=INFECTED").json();

            assertThat(names(page)).containsExactlyInAnyOrder("done.bin", "bad.bin");
        }

        @Test
        void the_search_is_a_case_insensitive_contains() {
            files.awaitingScan("Rapport-Annuel.pdf");
            files.awaitingScan("facture.pdf");

            assertThat(names(api.get("/api/v1/files?q=RAPPORT").json())).containsExactly("Rapport-Annuel.pdf");
        }

        @Test
        void a_search_term_over_the_contract_limit_is_refused() {
            assertThat(api.get("/api/v1/files?q=" + "x".repeat(101)).code()).isEqualTo("INVALID_PARAMETER");
        }

        @Test
        void sorting_by_name_follows_the_requested_direction() {
            files.awaitingScan("b.bin", 10, Instant.now());
            files.awaitingScan("a.bin", 10, Instant.now());
            files.awaitingScan("c.bin", 10, Instant.now());

            assertThat(names(api.get("/api/v1/files?sort=filename,asc").json()))
                    .containsExactly("a.bin", "b.bin", "c.bin");
            assertThat(names(api.get("/api/v1/files?sort=filename,desc").json()))
                    .containsExactly("c.bin", "b.bin", "a.bin");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/files/{fileId}")
    class Detail {

        @Test
        @DisplayName("a file still moving carries an ETag and a polling hint")
        void a_pending_file_invites_polling() {
            StoredFile file = files.awaitingScan("a.bin");

            HttpApi.Response response = api.get("/api/v1/files/" + file.id());

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("ETag")).hasValueSatisfying(tag -> assertThat(tag).startsWith("W/\""));
            assertThat(response.header("Cache-Control")).contains("no-cache");
            assertThat(response.header("Retry-After")).isPresent();
            JsonNode detail = response.json();
            assertThat(detail.path("sha256").asString()).isEqualTo(file.sha256().value());
            assertThat(detail.path("scanAttempts").asInt()).isZero();
            assertThat(detail.has("scan")).isTrue();
            assertThat(detail.path("scan").isNull()).isTrue();
            assertThat(detail.has("statusReason")).isTrue();
        }

        @Test
        @DisplayName("a terminal file is not polled: no Retry-After")
        void a_terminal_file_does_not_invite_polling() {
            StoredFile file = files.available("done.bin");

            HttpApi.Response response = api.get("/api/v1/files/" + file.id());

            assertThat(response.header("Retry-After")).isEmpty();
            assertThat(response.json().path("terminal").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("sending the ETag back yields a bodiless 304 while nothing moved")
        void an_unchanged_file_answers_304() {
            StoredFile file = files.awaitingScan("a.bin");
            String etag = api.get("/api/v1/files/" + file.id()).header("ETag").orElseThrow();

            HttpApi.Response unchanged = api.get("/api/v1/files/" + file.id(), "If-None-Match", etag);

            assertThat(unchanged.status()).isEqualTo(304);
            assertThat(unchanged.body()).isEmpty();
            assertThat(unchanged.header("Retry-After"))
                    .as("a client that revalidates receives little else than this hint")
                    .contains("2");
        }

        @Test
        @DisplayName("the polling hint grows with the file: 2 s for a small one, 26 s for 500 MB")
        void the_polling_hint_is_sized_to_the_file() {
            StoredFile small = files.awaitingScan("small.bin", 1_000_000, Instant.now());
            StoredFile large = files.awaitingScan("large.bin", 500_000_000, Instant.now());

            assertThat(api.get("/api/v1/files/" + small.id()).header("Retry-After")).contains("2");
            assertThat(api.get("/api/v1/files/" + large.id()).header("Retry-After")).contains("26");
        }

        @Test
        @DisplayName("once the file moves, the old ETag no longer matches")
        void a_transition_changes_the_etag() {
            StoredFile file = files.awaitingScan("a.bin");
            String before = api.get("/api/v1/files/" + file.id()).header("ETag").orElseThrow();

            queue.claimNextDue("worker", com.praxedo.securefiles.domain.file.valueobject.LeaseToken.random(),
                    Leases.fixed(Duration.ofMinutes(5)), 3);

            HttpApi.Response after = api.get("/api/v1/files/" + file.id(), "If-None-Match", before);
            assertThat(after.status()).isEqualTo(200);
            assertThat(after.json().path("status").asString()).isEqualTo("SCANNING");
        }

        @Test
        @DisplayName("an unknown file and a malformed identifier look exactly alike")
        void unknown_and_malformed_identifiers_both_answer_404() {
            HttpApi.Response unknown = api.get("/api/v1/files/" + java.util.UUID.randomUUID());
            HttpApi.Response malformed = api.get("/api/v1/files/not-a-uuid");

            assertThat(unknown.status()).isEqualTo(404);
            assertThat(unknown.code()).isEqualTo("FILE_NOT_FOUND");
            assertThat(malformed.status()).isEqualTo(404);
            assertThat(malformed.code()).isEqualTo("FILE_NOT_FOUND");
            assertThat(unknown.json().path("detail").asString())
                    .isEqualTo(malformed.json().path("detail").asString());
        }

        @Test
        @DisplayName("the content link is offered only for a file that can be served")
        void the_content_link_follows_downloadability() {
            StoredFile pending = files.promoting("pending.bin");
            StoredFile done = files.available("done.bin");

            JsonNode pendingLinks = api.get("/api/v1/files/" + pending.id()).json().path("links");
            JsonNode doneLinks = api.get("/api/v1/files/" + done.id()).json().path("links");

            assertThat(pendingLinks.path("self").asString()).isEqualTo("/api/v1/files/" + pending.id());
            assertThat(pendingLinks.has("content")).isFalse();
            assertThat(doneLinks.path("content").asString()).isEqualTo("/api/v1/files/" + done.id() + "/content");
        }

        @Test
        void an_infected_file_publishes_the_threat_that_blocked_it() {
            StoredFile blocked = files.infected("eicar.txt");

            JsonNode detail = api.get("/api/v1/files/" + blocked.id()).json();

            assertThat(detail.path("status").asString()).isEqualTo("INFECTED");
            assertThat(detail.path("downloadable").asBoolean()).isFalse();
            assertThat(detail.path("scan").path("result").asString()).isEqualTo("INFECTED");
            assertThat(detail.path("scan").path("threatName").asString()).isEqualTo("Eicar-Test-Signature");
            assertThat(detail.path("scan").path("signatureVersion").asString()).isEqualTo("28098");
        }

        @Test
        @DisplayName("a file whose promotion failed does not show its old clean verdict")
        void a_failed_promotion_does_not_publish_a_clean_verdict() {
            StoredFile promoting = files.promoting("broken-copy.bin");
            queue.writeTechnicalFailure(promoting.technicalFailure(1, Instant.now()),
                    promoting.currentLease().orElseThrow().token(), Duration.ofMinutes(30), "copy failed");

            JsonNode detail = api.get("/api/v1/files/" + promoting.id()).json();

            assertThat(detail.path("status").asString()).isEqualTo("FAILED");
            assertThat(detail.path("scan").isNull())
                    .as("the contract: scan is null for FAILED files")
                    .isTrue();
            assertThat(detail.path("statusReason").asString()).isEqualTo("SCAN_ATTEMPTS_EXHAUSTED");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/files/summary")
    class Summary {

        @Test
        @DisplayName("the six published counters are always present, at zero when empty")
        void every_status_is_counted() {
            JsonNode summary = api.get("/api/v1/files/summary").json();

            assertThat(summary.path("total").asLong()).isZero();
            List<String> keys = new ArrayList<>();
            summary.path("byStatus").propertyNames().forEach(keys::add);
            assertThat(keys).containsExactlyInAnyOrder(
                    "PENDING", "SCANNING", "AVAILABLE", "INFECTED", "UNSCANNABLE", "FAILED");
        }

        @Test
        void the_counters_follow_the_search() {
            files.available("rapport-1.pdf");
            files.infected("rapport-2.pdf");
            files.awaitingScan("facture.pdf");

            JsonNode summary = api.get("/api/v1/files/summary?q=rapport").json();

            assertThat(summary.path("total").asLong()).isEqualTo(2);
            assertThat(summary.path("byStatus").path("AVAILABLE").asLong()).isEqualTo(1);
            assertThat(summary.path("byStatus").path("INFECTED").asLong()).isEqualTo(1);
            assertThat(summary.path("byStatus").path("PENDING").asLong()).isZero();
        }
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.path("content").forEach(file -> names.add(file.path("filename").asString()));
        return names;
    }
}
