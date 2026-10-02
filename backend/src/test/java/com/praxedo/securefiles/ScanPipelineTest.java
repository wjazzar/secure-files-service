package com.praxedo.securefiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.praxedo.securefiles.testsupport.AntivirusContainer;
import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.SeaweedFsContainer;
import com.praxedo.securefiles.testsupport.TestContent;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The whole service, end to end, with nothing simulated: HTTP upload, real
 * PostgreSQL, real object storage, real antivirus, the worker running.
 *
 * <p>These two tests are the demonstration the exercise asks for, turned into
 * checks: a clean file ends up downloadable — and downloaded, byte for byte —
 * and EICAR, the test signature every engine recognises, never does. What the
 * engine does at its limits is measured separately, in
 * {@code AntivirusEngineLimitsTest}.
 *
 * <p>The context is discarded afterwards: its workers must not go on claiming
 * files that later test classes set up for themselves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "praxedo.worker.enabled=true",
        "praxedo.worker.poll-interval=200ms"
})
@DirtiesContext
class ScanPipelineTest extends FullStackTest {

    private static final Duration PATIENCE = Duration.ofSeconds(90);

    @DynamicPropertySource
    static void antivirus(DynamicPropertyRegistry registry) {
        registry.add("praxedo.antivirus.base-url", AntivirusContainer::baseUrl);
    }

    @Test
    @DisplayName("a clean file: pending, analysed, promoted — and the servable copy is the uploaded bytes")
    void a_clean_file_becomes_available() {
        long size = 1_500_000;
        JsonNode uploaded = api.post("/api/v1/files",
                HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(size)), size),
                "X-File-Name", "clean.bin", "Content-Type", "application/octet-stream").json();
        String id = uploaded.path("id").asString();

        JsonNode done = waitUntilTerminal(id);

        assertThat(done.path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(done.path("downloadable").asBoolean()).isTrue();
        assertThat(done.path("scan").path("result").asString()).isEqualTo("CLEAN");
        assertThat(done.path("scan").path("engine").asString()).isEqualTo("ClamAV");
        assertThat(done.path("scan").path("signatureVersion").asString()).isNotBlank();
        String content = done.path("links").path("content").asString();
        assertThat(content).isEqualTo("/api/v1/files/" + id + "/content");

        // Downloaded the way a browser does it: a plain navigation to the content link.
        HttpResponse<InputStream> download = api.getStream(content);
        assertThat(download.statusCode()).isEqualTo(200);
        try (InputStream served = download.body()) {
            assertThat(TestContent.digestOf(served)).isEqualTo(TestContent.digestOfGenerated(size));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    @DisplayName("⭐ EICAR: detected by the real engine, blocked for good, never copied to the servable area")
    void eicar_is_never_served() {
        byte[] eicar = AntivirusContainer.eicar().getBytes(StandardCharsets.US_ASCII);
        String id = api.post("/api/v1/files", HttpRequest.BodyPublishers.ofByteArray(eicar),
                "X-File-Name", "invoice.pdf").json().path("id").asString();

        JsonNode done = waitUntilTerminal(id);

        assertThat(done.path("status").asString()).isEqualTo("INFECTED");
        assertThat(done.path("downloadable").asBoolean()).isFalse();
        assertThat(done.path("scan").path("result").asString()).isEqualTo("INFECTED");
        assertThat(done.path("scan").path("threatName").asString()).containsIgnoringCase("eicar");
        assertThat(done.path("links").has("content")).isFalse();

        HttpApi.Response content = api.get("/api/v1/files/" + id + "/content");
        assertThat(content.status()).isEqualTo(409);
        assertThat(content.code()).isEqualTo("FILE_INFECTED");

        try (S3Client delivery = SeaweedFsContainer.client("praxedo-delivery", "praxedo-delivery-secret")) {
            assertThatExceptionOfType(S3Exception.class)
                    .as("nothing may exist under this key in the servable area")
                    .isThrownBy(() -> delivery.getObject(GetObjectRequest.builder()
                            .bucket(SeaweedFsContainer.SERVABLE).key(id).build()))
                    .satisfies(missing -> assertThat(missing.statusCode()).isEqualTo(404));
        }
    }

    private JsonNode waitUntilTerminal(String id) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            JsonNode file = api.get("/api/v1/files/" + id).json();
            if (file.path("terminal").asBoolean()) {
                return file;
            }
            sleep();
        }
        throw new AssertionError("File " + id + " did not reach a terminal state within " + PATIENCE);
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
