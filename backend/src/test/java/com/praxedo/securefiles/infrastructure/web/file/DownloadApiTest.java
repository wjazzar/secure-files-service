package com.praxedo.securefiles.infrastructure.web.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.service.FilePromotionService;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.testsupport.FileFixtures;
import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.ServableFiles;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.TestIdentityProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The way out, over real HTTP, from a real object storage.
 *
 * <p>Served files are real: uploaded through the API, then released by the
 * real promotion — the bytes are copied and checked against the digest the
 * upload computed. Only the antivirus is left out, and its verdict is the
 * single thing these tests write themselves.
 *
 * <p>The refusals are the point. A download service is judged less by what it
 * serves than by what it refuses: a file not analysed yet, a file found
 * infected, a file that belongs to somebody else, and the one situation that
 * should never happen — a row that says available and an object that is not
 * there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DownloadApiTest extends FullStackTest {

    private static final long SIZE = 100_000;

    @Autowired
    FileCatalog catalog;

    @Autowired
    FileWorkQueue queue;

    @Autowired
    FilePromotionService promotion;

    FileFixtures fixtures;
    ServableFiles servable;

    @BeforeEach
    void setUp() {
        fixtures = new FileFixtures(catalog, queue);
        servable = new ServableFiles(queue, promotion);
    }

    @Nested
    @DisplayName("GET /files/{id}/content — what is served")
    class Serving {

        @Test
        @DisplayName("200: the exact bytes, always as an attachment, never interpretable, never cached")
        void the_whole_file_is_served() throws IOException {
            StoredFile file = availableFile("Relevé d'été.pdf", SIZE);

            HttpResponse<InputStream> response = api.getStream(contentOf(file.id()));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/octet-stream");
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.headers().firstValue("Accept-Ranges")).contains("bytes");
            assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                    value -> assertThat(value).contains("no-store"));
            assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(SIZE);
            assertThat(response.headers().firstValue("Content-Disposition")).hasValue(
                    "attachment; filename=\"Relev_ d'_t_.pdf\"; filename*=UTF-8''Relev%C3%A9%20d%27%C3%A9t%C3%A9.pdf");
            try (InputStream body = response.body()) {
                assertThat(TestContent.digestOf(body)).isEqualTo(file.sha256().value());
            }
        }

        @Test
        @DisplayName("the digest is the ETag: the content never changes once served")
        void the_digest_is_the_etag() {
            StoredFile file = availableFile("export.csv", SIZE);

            HttpResponse<InputStream> response = api.getStream(contentOf(file.id()));

            assertThat(response.headers().firstValue("ETag")).contains("\"" + file.sha256().value() + "\"");
            close(response);
        }

        @Test
        @DisplayName("206: a single range, to resume an interrupted download")
        void a_range_is_served() throws IOException {
            StoredFile file = availableFile("big.bin", SIZE);

            HttpResponse<InputStream> response = api.getStream(contentOf(file.id()), "Range", "bytes=1000-1099");

            assertThat(response.statusCode()).isEqualTo(206);
            assertThat(response.headers().firstValue("Content-Range")).contains("bytes 1000-1099/" + SIZE);
            assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(100);
            assertThat(body(response)).isEqualTo(expectedBytes(1000, 1099));
        }

        @Test
        @DisplayName("206: an open-ended range runs to the end of the file")
        void an_open_range_runs_to_the_end() throws IOException {
            StoredFile file = availableFile("big.bin", SIZE);

            HttpResponse<InputStream> response = api.getStream(contentOf(file.id()), "Range", "bytes=" + (SIZE - 26) + "-");

            assertThat(response.statusCode()).isEqualTo(206);
            assertThat(response.headers().firstValue("Content-Range"))
                    .contains("bytes " + (SIZE - 26) + "-" + (SIZE - 1) + "/" + SIZE);
            assertThat(body(response)).isEqualTo(expectedBytes(SIZE - 26, SIZE - 1));
        }

        @Test
        @DisplayName("416: a range past the end, with the real length so the client can correct itself")
        void a_range_past_the_end_is_refused() {
            StoredFile file = availableFile("big.bin", SIZE);

            HttpApi.Response response = api.get(contentOf(file.id()), "Range", "bytes=" + SIZE + "-");

            assertThat(response.status()).isEqualTo(416);
            assertThat(response.code()).isEqualTo("RANGE_NOT_SATISFIABLE");
            assertThat(response.header("Content-Range")).contains("bytes */" + SIZE);
        }

        @Test
        @DisplayName("200: several ranges, or a malformed header, are ignored — the whole file is served")
        void unsupported_ranges_are_ignored() {
            StoredFile file = availableFile("big.bin", SIZE);

            for (String range : new String[] {"bytes=0-9,20-29", "bytes=-500", "lines=1-2", "bytes=50-10"}) {
                HttpResponse<InputStream> response = api.getStream(contentOf(file.id()), "Range", range);
                assertThat(response.statusCode()).as(range).isEqualTo(200);
                assertThat(response.headers().firstValueAsLong("Content-Length")).as(range).hasValue(SIZE);
                close(response);
            }
        }

        @Test
        @DisplayName("every download leaves a trace in the audit trail, before the first byte, with its range")
        void downloads_are_audited() {
            StoredFile file = availableFile("audited.bin", SIZE);

            close(api.getStream(contentOf(file.id())));
            close(api.getStream(contentOf(file.id()), "Range", "bytes=0-9"));

            assertThat(jdbc.sql("""
                    SELECT event_type || ' ' || actor || ' ' || details::text
                      FROM file_audit_event
                     WHERE file_id = :id AND event_type LIKE 'DOWNLOAD%'
                     ORDER BY id
                    """).param("id", file.id().value()).query(String.class).list())
                    .containsExactly(
                            "DOWNLOAD_SERVED " + TestIdentityProvider.USER + " {\"range\": \"whole\"}",
                            "DOWNLOAD_SERVED " + TestIdentityProvider.USER + " {\"range\": \"bytes=0-9\"}");
        }
    }

    @Nested
    @DisplayName("GET /files/{id}/content — what is refused")
    class Refusals {

        @ParameterizedTest(name = "{0} → 409 {1}")
        @CsvSource({
                "pending,     FILE_NOT_READY,   PENDING,     true",
                "retryWait,   FILE_NOT_READY,   PENDING,     true",
                "scanning,    FILE_NOT_READY,   SCANNING,    true",
                "promoting,   FILE_NOT_READY,   SCANNING,    true",
                "infected,    FILE_INFECTED,    INFECTED,    false",
                "unscannable, FILE_UNSCANNABLE, UNSCANNABLE, false",
                "failed,      FILE_SCAN_FAILED, FAILED,      false"
        })
        @DisplayName("⭐ the state is read at the moment of serving: 409 for every file that is not available, "
                + "with a Retry-After only where waiting helps")
        void nothing_but_an_available_file(String state, String code, String fileStatus, boolean retryable) {
            StoredFile file = inState(state);

            HttpApi.Response response = api.get(contentOf(file.id()));

            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo(code);
            assertThat(response.json().path("fileStatus").asString()).isEqualTo(fileStatus);
            assertThat(response.header("Retry-After").isPresent()).isEqualTo(retryable);
        }

        @Test
        @DisplayName("404 for an unknown file, a malformed identifier, and a file owned by someone else — indistinguishably")
        void nothing_the_caller_cannot_see() {
            StoredFile someoneElses = fixtures.awaitingScan("theirs.pdf", SIZE, Instant.now(), new OwnerId("someone-else"));

            for (String id : new String[] {UUID.randomUUID().toString(), "not-an-id", someoneElses.id().value().toString()}) {
                HttpApi.Response response = api.get("/api/v1/files/" + id + "/content");
                assertThat(response.status()).as(id).isEqualTo(404);
                assertThat(response.code()).as(id).isEqualTo("FILE_NOT_FOUND");
            }
        }

        @Test
        @DisplayName("405 on HEAD: the object is not opened, nothing is read, nothing is audited")
        void head_is_refused_before_the_object_is_opened() {
            StoredFile file = availableFile("probed.bin", SIZE);

            HttpApi.Response response = api.head(contentOf(file.id()));

            assertThat(response.status()).isEqualTo(405);
            assertThat(response.header("Allow")).contains("GET");
            assertThat(jdbc.sql("SELECT count(*) FROM file_audit_event WHERE file_id = :id AND event_type LIKE 'DOWNLOAD%'")
                    .param("id", file.id().value()).query(Long.class).single()).isZero();
        }

        @Test
        @DisplayName("a refused download is not audited: nothing was served")
        void refusals_leave_no_download_trace() {
            StoredFile pending = fixtures.awaitingScan("later.pdf");

            api.get(contentOf(pending.id()));

            assertThat(jdbc.sql("SELECT count(*) FROM file_audit_event WHERE file_id = :id AND event_type LIKE 'DOWNLOAD%'")
                    .param("id", pending.id().value()).query(Long.class).single()).isZero();
        }

        @Test
        @DisplayName("⚠ available in the database, absent from the storage: an anomaly, answered 503, never papered over")
        void an_available_file_without_content_is_an_outage() {
            StoredFile ghost = fixtures.available("ghost.bin");

            HttpApi.Response response = api.get(contentOf(ghost.id()));

            assertThat(response.status()).isEqualTo(503);
            assertThat(response.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(response.header("Retry-After")).isPresent();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private StoredFile availableFile(String name, long size) {
        HttpApi.Response uploaded = api.post("/api/v1/files",
                HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(size)), size),
                "X-File-Name", URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"),
                "Content-Type", "application/octet-stream");
        assertThat(uploaded.status()).isEqualTo(202);
        StoredFile available = servable.promoteNextDue();
        assertThat(available.id().value().toString()).isEqualTo(uploaded.json().path("id").asString());
        return available;
    }

    private StoredFile inState(String state) {
        return switch (state) {
            case "pending" -> fixtures.awaitingScan("file.pdf");
            case "retryWait" -> fixtures.retryWait("file.pdf");
            case "scanning" -> fixtures.scanning("file.pdf");
            case "promoting" -> fixtures.promoting("file.pdf");
            case "infected" -> fixtures.infected("file.pdf");
            case "unscannable" -> fixtures.unscannable("file.pdf");
            case "failed" -> fixtures.failedFinal("file.pdf");
            default -> throw new IllegalArgumentException(state);
        };
    }

    private static String contentOf(FileId id) {
        return "/api/v1/files/" + id.value() + "/content";
    }

    /** What {@link TestContent#generated} holds between two positions, inclusive. */
    private static byte[] expectedBytes(long first, long last) {
        try (InputStream content = TestContent.generated(last + 1)) {
            content.skipNBytes(first);
            return content.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static byte[] body(HttpResponse<InputStream> response) throws IOException {
        try (InputStream body = response.body()) {
            return body.readAllBytes();
        }
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
