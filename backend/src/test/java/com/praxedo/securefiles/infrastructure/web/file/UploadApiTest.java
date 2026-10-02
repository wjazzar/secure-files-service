package com.praxedo.securefiles.infrastructure.web.file;

import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.RawHttp;
import com.praxedo.securefiles.testsupport.SeaweedFsContainer;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.TestIdentityProvider;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/files} over real HTTP, into a real PostgreSQL and a real
 * object storage.
 *
 * <p>The malformed requests — no length, a length never honoured, a body cut
 * short — are sent on a raw socket: a well-behaved client refuses to produce
 * them, which is exactly why they need testing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadApiTest extends FullStackTest {

    @Nested
    @DisplayName("an accepted upload")
    class Accepted {

        @Test
        @DisplayName("202, Location, a polling hint, and a pending file that cannot be downloaded")
        void a_file_is_accepted_and_queued() {
            long size = 300_000;

            HttpApi.Response response = upload("rapport.pdf", size, null);

            assertThat(response.status()).isEqualTo(202);
            JsonNode file = response.json();
            assertThat(response.header("Location")).contains("/api/v1/files/" + file.path("id").asString());
            assertThat(response.header("Retry-After")).isPresent();
            assertThat(file.path("status").asString()).isEqualTo("PENDING");
            assertThat(file.path("downloadable").asBoolean()).isFalse();
            assertThat(file.path("terminal").asBoolean()).isFalse();
            assertThat(file.path("scanAttempts").asInt()).isZero();
            assertThat(file.path("scan").isNull()).isTrue();
            assertThat(file.path("sizeBytes").asLong()).isEqualTo(size);
        }

        @Test
        @DisplayName("the digest announced is the digest of the bytes the storage actually holds")
        void the_stored_object_is_exactly_what_was_sent() {
            long size = 2L * 1024 * 1024 + 3;

            JsonNode file = upload("data.bin", size, null).json();

            assertThat(file.path("sha256").asString()).isEqualTo(TestContent.digestOfGenerated(size));
            try (S3Client worker = SeaweedFsContainer.client("praxedo-worker", "praxedo-worker-secret");
                 InputStream stored = worker.getObject(GetObjectRequest.builder()
                         .bucket(SeaweedFsContainer.QUARANTINE).key(file.path("id").asString()).build())) {
                assertThat(TestContent.digestOf(stored)).isEqualTo(TestContent.digestOfGenerated(size));
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        @Test
        @DisplayName("the name is decoded, then sanitised: a path never survives")
        void the_name_is_decoded_and_sanitised() {
            String nasty = "../../relevé été 2026 #1+2.pdf";

            JsonNode accepted = upload(nasty, 10, null).json();

            assertThat(accepted.path("filename").asString()).isEqualTo("relevé été 2026 #1+2.pdf");
        }

        @Test
        @DisplayName("the type declared by the client is ignored: only the bytes speak")
        void the_declared_type_plays_no_part() {
            byte[] disguised = "MZ\u0090\u0000 not really a pdf".getBytes(StandardCharsets.ISO_8859_1);

            JsonNode file = api.post("/api/v1/files", HttpRequest.BodyPublishers.ofByteArray(disguised),
                    "X-File-Name", "invoice.pdf",
                    "Content-Type", "application/pdf").json();

            assertThat(file.path("contentType").asString()).isEqualTo("application/x-msdownload");
        }
    }

    @Nested
    @DisplayName("refused on its headers")
    class RefusedOnHeaders {

        @Test
        void a_missing_file_name_is_refused() {
            HttpApi.Response response = api.post("/api/v1/files", HttpRequest.BodyPublishers.ofString("hello"));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.code()).isEqualTo("INVALID_FILE_NAME");
        }

        @Test
        void a_name_that_holds_nothing_usable_is_refused() {
            HttpApi.Response response = api.post("/api/v1/files", HttpRequest.BodyPublishers.ofString("hello"),
                    "X-File-Name", encodeUriComponent("\u0000\u0001"));

            assertThat(response.code()).isEqualTo("INVALID_FILE_NAME");
        }

        @Test
        void an_empty_file_is_refused() {
            HttpApi.Response response = api.post("/api/v1/files", HttpRequest.BodyPublishers.noBody(),
                    "X-File-Name", "empty.txt");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.code()).isEqualTo("EMPTY_FILE");
        }

        @Test
        @DisplayName("no Content-Length at all: 411, the size could not be checked before reading")
        void a_chunked_upload_is_refused() {
            RawHttp.Answer answer = RawHttp.exchange(port, """
                    POST /api/v1/files HTTP/1.1
                    Host: localhost
                    Authorization: Bearer %s
                    X-File-Name: chunked.bin
                    Transfer-Encoding: chunked
                    Connection: close
                    """.formatted(TestIdentityProvider.accessToken()), "5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII), false);

            assertThat(answer.status()).isEqualTo(411);
            assertThat(answer.mentions("LENGTH_REQUIRED")).isTrue();
        }

        @Test
        @DisplayName("600 MB announced: 413 before a single byte of the body is read")
        void an_oversized_upload_is_refused_on_its_headers() {
            RawHttp.Answer answer = RawHttp.exchange(port, """
                    POST /api/v1/files HTTP/1.1
                    Host: localhost
                    Authorization: Bearer %s
                    X-File-Name: huge.bin
                    Content-Length: 629145600
                    Connection: close
                    """.formatted(TestIdentityProvider.accessToken()), new byte[0], false);

            assertThat(answer.status()).isEqualTo(413);
            assertThat(answer.mentions("FILE_TOO_LARGE")).isTrue();
            assertThat(answer.mentions("\"maxFileSizeBytes\":524288000")).isTrue();
        }

        @Test
        void a_malformed_idempotency_key_is_refused() {
            HttpApi.Response response = api.post("/api/v1/files", HttpRequest.BodyPublishers.ofString("hello"),
                    "X-File-Name", "a.txt", "Idempotency-Key", "short");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.code()).isEqualTo("INVALID_PARAMETER");
        }
    }

    @Nested
    @DisplayName("a body that lies about its length")
    class LyingBody {

        /**
         * The client announced 1000 bytes, sent 400, and closed its side.
         *
         * <p>What is asserted is what the service guarantees: a {@code 400}, and
         * <strong>nothing kept</strong> — no row, no object. What is deliberately
         * not asserted is the problem document: the use case does answer
         * {@code CONTENT_LENGTH_MISMATCH}, but once the client has aborted its
         * body, Tomcat marks the exchange in error and substitutes its own error
         * page. The status survives, the body does not — and the client that
         * produced this situation has gone and would not read it anyway.
         */
        @Test
        @DisplayName("1000 bytes announced, 400 sent, connection half-closed: refused, and nothing kept")
        void a_truncated_body_keeps_nothing() {
            int objectsBefore = quarantinedObjects();

            RawHttp.Answer answer = RawHttp.exchange(port, """
                    POST /api/v1/files HTTP/1.1
                    Host: localhost
                    Authorization: Bearer %s
                    X-File-Name: cut.bin
                    Content-Length: 1000
                    """.formatted(TestIdentityProvider.accessToken()), new byte[400], true);

            assertThat(answer.status()).as(answer.raw()).isEqualTo(400);
            assertThat(jdbc.sql("SELECT count(*) FROM stored_file").query(Integer.class).single()).isZero();
            assertThat(quarantinedObjects()).as("no object left in quarantine").isEqualTo(objectsBefore);
        }
    }

    private static int quarantinedObjects() {
        try (S3Client worker = SeaweedFsContainer.client("praxedo-worker", "praxedo-worker-secret")) {
            return worker.listObjectsV2Paginator(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                    .bucket(SeaweedFsContainer.QUARANTINE).build()).contents().stream().mapToInt(object -> 1).sum();
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the same key twice: one file, and the retry sees the same one")
        void a_retry_with_the_same_key_returns_the_same_file() {
            String key = UUID.randomUUID().toString();

            JsonNode first = upload("once.bin", 1_000, key).json();
            HttpApi.Response retry = upload("once.bin", 1_000, key);

            assertThat(retry.status()).isEqualTo(202);
            assertThat(retry.json().path("id").asString()).isEqualTo(first.path("id").asString());
            assertThat(jdbc.sql("SELECT count(*) FROM stored_file").query(Integer.class).single()).isEqualTo(1);
        }

        @Test
        void a_key_reused_for_another_file_is_refused() {
            String key = UUID.randomUUID().toString();
            upload("first.bin", 1_000, key);

            HttpApi.Response reused = upload("second.bin", 1_000, key);

            assertThat(reused.status()).isEqualTo(422);
            assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }
    }

    private HttpApi.Response upload(String name, long size, String idempotencyKey) {
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(size)), size);
        return idempotencyKey == null
                ? api.post("/api/v1/files", body, "X-File-Name", encodeUriComponent(name),
                        "Content-Type", "application/octet-stream")
                : api.post("/api/v1/files", body, "X-File-Name", encodeUriComponent(name),
                        "Content-Type", "application/octet-stream", "Idempotency-Key", idempotencyKey);
    }

    /** What the browser's encodeURIComponent produces. */
    private static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
