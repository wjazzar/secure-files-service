package com.praxedo.securefiles.config;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.praxedo.securefiles.application.file.exception.StorageUnavailableException;
import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.application.file.port.out.QuarantineWriter;
import com.praxedo.securefiles.application.file.port.out.ServableReader;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.infrastructure.storage.common.config.StorageProperties;
import com.praxedo.securefiles.testsupport.TestContent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Who retries a storage call that failed — against a storage answering
 * {@code 503 SlowDown}, as an overloaded object store does.
 *
 * <p>The saturation campaign of {@code docs/capacity-planning} found the
 * defect these tests pin: a body streamed from a socket is read once, yet the
 * SDK retried the promotion copy underneath and asked for it a second time. The
 * copy then died on an {@code IllegalStateException} that no port promises,
 * and its file sat in {@code PROMOTING} until the promotion lease expired,
 * instead of going back to the queue.
 *
 * <p>The clients are taken from the production wiring, so these tests check
 * the policy each identity really gets.
 */
class StorageRetriesTest {

    private static final ObjectKey KEY = new ObjectKey("0b9f6f5e-2c3a-4d7e-9a41-5f0c2b7d8e11");
    private static final long SIZE = 256 * 1024;
    private static final String QUARANTINE = "quarantine";
    private static final String SERVABLE = "servable";

    private WireMockServer storage;
    private StorageProperties properties;
    private final StorageConfiguration wiring = new StorageConfiguration();

    @BeforeEach
    void overloadedStorage() {
        storage = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        storage.start();
        storage.stubFor(any(anyUrl()).willReturn(slowDown()));
        properties = new StorageProperties(URI.create(storage.baseUrl()), "us-east-1", QUARANTINE, SERVABLE,
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                new StorageProperties.Credentials("praxedo-ingest", "ingest-secret"),
                new StorageProperties.Credentials("praxedo-worker", "worker-secret"),
                new StorageProperties.Credentials("praxedo-delivery", "delivery-secret"));
    }

    @AfterEach
    void stop() {
        storage.stop();
    }

    @Test
    @DisplayName("⭐ a promotion copy the storage refuses is a storage failure after one attempt — the queue retries it")
    void a_refused_promotion_copy_goes_back_to_the_queue() {
        WorkerStorage worker = wiring.workerStorage(properties);

        assertThatExceptionOfType(StorageUnavailableException.class)
                .isThrownBy(() -> worker.writeServable(KEY, TestContent.generated(SIZE), SIZE));

        storage.verify(1, putRequestedFor(urlPathEqualTo("/" + SERVABLE + "/" + KEY.value())));
    }

    @Test
    @DisplayName("an upload the storage refuses is a storage failure after one attempt — the client retries it")
    void a_refused_upload_is_answered_as_unavailable() {
        QuarantineWriter ingest = wiring.quarantineWriter(properties);

        assertThatExceptionOfType(StorageUnavailableException.class)
                .isThrownBy(() -> ingest.write(KEY, TestContent.generated(SIZE), SIZE));

        storage.verify(1, putRequestedFor(urlPathEqualTo("/" + QUARANTINE + "/" + KEY.value())));
    }

    @Test
    @DisplayName("a download read is retried by the SDK: reading again replays nothing")
    void a_refused_download_read_is_retried() {
        ServableReader delivery = wiring.servableReader(properties);

        assertThatExceptionOfType(StorageUnavailableException.class)
                .isThrownBy(() -> delivery.open(KEY, ByteRange.WHOLE_OBJECT));

        storage.verify(3, headRequestedFor(urlPathEqualTo("/" + SERVABLE + "/" + KEY.value())));
    }

    private static ResponseDefinitionBuilder slowDown() {
        return aResponse()
                .withStatus(503)
                .withHeader("Content-Type", "application/xml")
                .withBody("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Error><Code>SlowDown</Code><Message>Please reduce your request rate.</Message></Error>""");
    }
}
