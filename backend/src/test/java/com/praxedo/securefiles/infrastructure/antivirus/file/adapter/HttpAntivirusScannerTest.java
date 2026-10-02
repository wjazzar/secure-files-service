package com.praxedo.securefiles.infrastructure.antivirus.file.adapter;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner.ScanOutcome;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.infrastructure.antivirus.common.config.AntivirusClients;
import com.praxedo.securefiles.infrastructure.antivirus.common.config.AntivirusProperties;
import com.praxedo.securefiles.testsupport.TestContent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The antivirus adapter against a simulated API — for everything the real
 * engine cannot be made to do on demand: fall silent, answer too slowly, send
 * garbage, report a limit.
 *
 * <p>The answers used here are the ones measured on the real engine
 * ({@code infra/README.md} §3). The real engine itself is exercised by the
 * EICAR test of the pipeline.
 */
class HttpAntivirusScannerTest {

    private static final String VERSION = """
            { "Clamav": "1.4.6", "Signature": "28098" , "Signature_date": "Thu Aug 20 08:24:22 2026" }""";

    private WireMockServer engine;
    private AntivirusScanner scanner;

    @BeforeEach
    void start() {
        engine = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        engine.start();
        engine.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200)));
        engine.stubFor(get(urlEqualTo("/version")).willReturn(aResponse().withStatus(200).withBody(VERSION)));
        scanner = scannerFor(URI.create(engine.baseUrl()), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        engine.stop();
    }

    @Nested
    @DisplayName("the translation table")
    class Translation {

        @Test
        void a_200_is_clean_and_records_the_signatures_used() {
            answer(200, "{OK   200}");

            ScanOutcome outcome = scan(100);

            assertThat(outcome.result()).isEqualTo(ScanResult.CLEAN);
            assertThat(outcome.engine()).isEqualTo("ClamAV");
            assertThat(outcome.engineVersion()).isEqualTo("1.4.6");
            assertThat(outcome.signatureVersion()).isEqualTo("28098");
        }

        @Test
        void a_406_with_a_threat_name_is_infected() {
            answer(406, "{FOUND Eicar-Test-Signature  406}");

            ScanOutcome outcome = scan(100);

            assertThat(outcome.result()).isEqualTo(ScanResult.INFECTED);
            assertThat(outcome.detail()).isEqualTo("Eicar-Test-Signature");
        }

        @Test
        @DisplayName("a 406 reporting a limit is UNSCANNABLE — neither a threat nor, above all, clean")
        void a_limit_reported_as_a_detection_is_unscannable() {
            answer(406, "{FOUND Heuristics.Limits.Exceeded.MaxScanSize  406}");

            ScanOutcome outcome = scan(100);

            assertThat(outcome.result()).isEqualTo(ScanResult.UNSCANNABLE);
            assertThat(outcome.detail()).startsWith("Heuristics.Limits.Exceeded");
        }

        @Test
        @DisplayName("a 406 whose body cannot be read is recorded as infected: the safe direction")
        void an_unreadable_detection_errs_on_the_safe_side() {
            answer(406, "garbage that is not the expected shape");

            ScanOutcome outcome = scan(100);

            assertThat(outcome.result()).isEqualTo(ScanResult.INFECTED);
            assertThat(outcome.detail()).isNull();
        }

        @Test
        void a_412_is_unscannable() {
            answer(412, "{PARSE ERROR  412}");

            assertThat(scan(100).result()).isEqualTo(ScanResult.UNSCANNABLE);
        }

        @Test
        @DisplayName("a 413 is a failure, never a verdict: the wrapper also sends it when the stream breaks")
        void a_413_is_a_failure() {
            answer(413, "{PARSE ERROR File size limit exceeded 413}");

            assertThatExceptionOfType(ScannerUnavailableException.class).isThrownBy(() -> scan(100));
        }

        @Test
        void a_server_error_is_a_failure() {
            answer(500, "boom");

            assertThatExceptionOfType(ScannerUnavailableException.class).isThrownBy(() -> scan(100));
        }
    }

    @Nested
    @DisplayName("the failures only a simulation can produce")
    class Failures {

        @Test
        @DisplayName("an engine slower than the read timeout is a failure, not a hang")
        void a_silent_engine_times_out() {
            engine.stubFor(post(urlEqualTo("/scanHandlerBody"))
                    .willReturn(aResponse().withStatus(200).withBody("{OK   200}").withFixedDelay(5_000)));
            AntivirusScanner impatient = scannerFor(URI.create(engine.baseUrl()), Duration.ofMillis(500));

            assertThatExceptionOfType(ScannerUnavailableException.class)
                    .isThrownBy(() -> impatient.scan(TestContent.generated(100), 100));
        }

        @Test
        void a_connection_reset_mid_answer_is_a_failure() {
            engine.stubFor(post(urlEqualTo("/scanHandlerBody"))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatExceptionOfType(ScannerUnavailableException.class).isThrownBy(() -> scan(100));
        }

        @Test
        @DisplayName("an engine that is not there: unavailable, and the health gate says so")
        void an_absent_engine_is_unavailable() {
            AntivirusScanner nobody = scannerFor(URI.create("http://localhost:1"), Duration.ofSeconds(1));

            assertThat(nobody.isAvailable()).isFalse();
            assertThatExceptionOfType(ScannerUnavailableException.class)
                    .isThrownBy(() -> nobody.scan(TestContent.generated(10), 10));
        }

        @Test
        @DisplayName("without a signature version, not even a clean verdict can be recorded")
        void a_missing_signature_version_is_a_failure() {
            engine.stubFor(get(urlEqualTo("/version")).willReturn(aResponse().withStatus(200)
                    .withBody("{ \"Clamav\": \"1.4.6\" }")));
            answer(200, "{OK   200}");

            assertThatExceptionOfType(ScannerUnavailableException.class).isThrownBy(() -> scan(100));
        }
    }

    @Nested
    @DisplayName("the exchange itself")
    class Exchange {

        @Test
        @DisplayName("the whole file is streamed, with its exact length announced")
        void the_body_arrives_whole_with_its_length() {
            answer(200, "{OK   200}");

            scan(300_000);

            engine.verify(postRequestedFor(urlEqualTo("/scanHandlerBody"))
                    .withHeader("Content-Length", equalTo("300000")));
            byte[] received = engine.getAllServeEvents().stream()
                    .filter(event -> event.getRequest().getUrl().equals("/scanHandlerBody"))
                    .findFirst().orElseThrow().getRequest().getBody();
            assertThat(TestContent.digestOf(new java.io.ByteArrayInputStream(received)))
                    .isEqualTo(TestContent.digestOfGenerated(300_000));
        }

        @Test
        @DisplayName("the engine version is read once, then cached")
        void the_version_is_cached() {
            answer(200, "{OK   200}");

            scan(10);
            scan(10);
            scan(10);

            engine.verify(1, getRequestedFor(urlEqualTo("/version")));
        }
    }

    private ScanOutcome scan(long size) {
        return scanner.scan(TestContent.generated(size), size);
    }

    private void answer(int status, String body) {
        engine.stubFor(post(urlEqualTo("/scanHandlerBody"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private static AntivirusScanner scannerFor(URI baseUrl, Duration readTimeout) {
        AntivirusProperties properties = new AntivirusProperties(
                baseUrl, Duration.ofSeconds(1), readTimeout, Duration.ofSeconds(1), Duration.ofMinutes(1));
        return AntivirusClients.httpScanner(properties, Clock.systemUTC());
    }
}
