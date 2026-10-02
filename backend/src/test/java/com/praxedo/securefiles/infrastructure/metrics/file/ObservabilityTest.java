package com.praxedo.securefiles.infrastructure.metrics.file;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.service.FilePromotionService;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.ServableFiles;
import com.praxedo.securefiles.testsupport.TestContent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What operations can see: the metrics of {@code ARCHITECTURE.md} §12, the
 * health of the invariant, and one identifier per request.
 *
 * <p>Metrics export is switched back on for this context — Spring Boot turns it
 * off in tests — so the Prometheus endpoint answers as it would in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.defaults.metrics.export.enabled=true")
class ObservabilityTest extends FullStackTest {

    private static final long SIZE = 10_000;

    @Autowired
    MeterRegistry registry;

    @Autowired
    FileWorkQueue queue;

    @Autowired
    FilePromotionService promotion;

    @Test
    @DisplayName("the queue gauges follow the database, and the invariant gauge reads zero")
    void queue_and_invariant_gauges() {
        assertThat(gauge("praxedo.queue.depth")).isZero();
        assertThat(gauge("praxedo.queue.depth.bytes")).isZero();
        assertThat(gauge("praxedo.queue.oldest_pending_age")).isZero();

        upload();
        upload();

        assertThat(gauge("praxedo.queue.depth")).isEqualTo(2);
        assertThat(gauge("praxedo.queue.depth.bytes")).isEqualTo(2 * SIZE);
        assertThat(gauge("praxedo.queue.oldest_pending_age")).isGreaterThanOrEqualTo(0);
        assertThat(gauge("praxedo.invariant.violations")).isZero();
    }

    @Test
    @DisplayName("⭐ a file that reaches its final state records its lag, and its bytes into the throughput")
    void lag_and_throughput_per_file() {
        Timer lag = registry.get("praxedo.pipeline.lag").tag("outcome", "AVAILABLE").timer();
        long filesBefore = lag.count();
        double bytesBefore = registry.get("praxedo.pipeline.completed.bytes").tag("outcome", "AVAILABLE")
                .counter().count();

        upload();
        new ServableFiles(queue, promotion).promoteNextDue();

        assertThat(lag.count()).isEqualTo(filesBefore + 1);
        assertThat(lag.max(TimeUnit.MILLISECONDS)).isPositive();
        assertThat(registry.get("praxedo.pipeline.completed.bytes").tag("outcome", "AVAILABLE").counter().count()
                - bytesBefore).isEqualTo(SIZE);
        // Out of the lag once available.
        assertThat(gauge("praxedo.queue.depth")).isZero();
        assertThat(gauge("praxedo.queue.depth.bytes")).isZero();
    }

    @Test
    @DisplayName("bytes in and bytes out are counted")
    void throughput_counters() throws IOException {
        double receivedBefore = counter("praxedo.upload.bytes");
        double servedBefore = counter("praxedo.download.bytes");

        upload();
        StoredFile available = new ServableFiles(queue, promotion).promoteNextDue();
        HttpResponse<InputStream> download = api.getStream("/api/v1/files/" + available.id() + "/content");
        try (InputStream body = download.body()) {
            body.transferTo(OutputSink.INSTANCE);
        }

        assertThat(counter("praxedo.upload.bytes") - receivedBefore).isEqualTo(SIZE);
        assertThat(counter("praxedo.download.bytes") - servedBefore).isEqualTo(SIZE);
    }

    @Test
    @DisplayName("every verdict series exists from the start, at zero — a dashboard shows 'none yet', not 'no data'")
    void verdict_series_are_registered_up_front() {
        for (String result : new String[] {"CLEAN", "INFECTED", "UNSCANNABLE"}) {
            assertThat(registry.find("praxedo.scan.verdict").tag("result", result).counter()).as(result).isNotNull();
        }
        assertThat(registry.find("praxedo.antivirus.available").gauge()).isNotNull();
        assertThat(registry.find("praxedo.scan.inflight").gauge()).isNotNull();
        for (String outcome : new String[] {"AVAILABLE", "INFECTED", "UNSCANNABLE", "FAILED"}) {
            assertThat(registry.find("praxedo.pipeline.lag").tag("outcome", outcome).timer()).as(outcome).isNotNull();
            assertThat(registry.find("praxedo.pipeline.completed.bytes").tag("outcome", outcome).counter())
                    .as(outcome).isNotNull();
        }
    }

    @Test
    @DisplayName("the Prometheus endpoint publishes them on the management port, and the health includes the invariant")
    void prometheus_and_health() {
        HttpApi.Response prometheus = management.get("/actuator/prometheus");
        HttpApi.Response health = management.get("/actuator/health");

        assertThat(prometheus.status()).isEqualTo(200);
        assertThat(prometheus.body()).contains("praxedo_queue_depth", "praxedo_invariant_violations{application=\"praxedo-securefiles\"} 0.0",
                "praxedo_queue_oldest_pending_age_seconds", "praxedo_queue_depth_bytes",
                // Histogram buckets: percentiles across nodes are computed in Prometheus.
                "praxedo_pipeline_lag_seconds_bucket", "praxedo_pipeline_completed_bytes_total",
                "praxedo_scan_bytes_total", "http_server_requests_seconds_bucket");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.json().path("status").asString()).isEqualTo("UP");
    }

    /**
     * Anonymous, the answer is a {@code 401} rather than a {@code 404}: the
     * unknown path falls to the error page, which is not public either — so an
     * outsider does not even learn that nothing is there.
     */
    @Test
    @DisplayName("⭐ the API's port does not serve the actuator: no metrics there, with or without a token (audit S-08)")
    void the_api_port_does_not_serve_the_actuator() {
        for (String path : new String[] {"/actuator/prometheus", "/actuator/metrics", "/actuator/info", "/actuator/health"}) {
            HttpApi.Response anonymous = new HttpApi(port).get(path);
            HttpApi.Response authenticated = api.get(path);

            assertThat(anonymous.status()).as(path + " anonymous").isEqualTo(401);
            assertThat(authenticated.status()).as(path + " with a token").isEqualTo(404);
            assertThat(anonymous.body() + authenticated.body()).as(path).doesNotContain("praxedo_");
        }
    }

    @Test
    @DisplayName("each response carries a request identifier; a well-formed one from the caller is kept, anything else replaced")
    void request_identifier() {
        HttpApi.Response generated = api.get("/api/v1/files");
        HttpApi.Response kept = api.get("/api/v1/files", "X-Request-Id", "gateway-trace-0001");
        HttpApi.Response replaced = api.get("/api/v1/files", "X-Request-Id", "<script>");

        assertThat(generated.header("X-Request-Id")).hasValueSatisfying(id -> assertThat(id).hasSize(36));
        assertThat(kept.header("X-Request-Id")).contains("gateway-trace-0001");
        assertThat(replaced.header("X-Request-Id")).hasValueSatisfying(id -> assertThat(id).isNotEqualTo("<script>"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void upload() {
        HttpApi.Response response = api.post("/api/v1/files",
                HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(SIZE)), SIZE),
                "X-File-Name", "observed.bin", "Content-Type", "application/octet-stream");
        assertThat(response.status()).isEqualTo(202);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    /** Discards what it is given: the body only has to be read to the end. */
    private static final class OutputSink extends java.io.OutputStream {
        static final OutputSink INSTANCE = new OutputSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
        }
    }
}
