package com.praxedo.securefiles.infrastructure.antivirus.common.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.praxedo.securefiles.infrastructure.antivirus.file.adapter.HttpAntivirusScanner;

/**
 * Builds the antivirus adapter and its two HTTP clients: one sized for a
 * 500 MB analysis, one for the health gate, which must answer in seconds or be
 * considered down.
 *
 * <p>The JDK client is used, forced to HTTP/1.1: it streams a request body of
 * known length without buffering it, and the engine's wrapper does not speak
 * HTTP/2 over plain text.
 *
 * <p>Which adapter answers the {@code AntivirusScanner} port — this one,
 * measured, or the capacity-test stand-in — is decided by the composition, in
 * {@code config.AntivirusConfiguration}; how it talks HTTP is decided here.
 */
public final class AntivirusClients {

    private AntivirusClients() {
    }

    /** The adapter alone, unmeasured — what the tests exercise. */
    public static HttpAntivirusScanner httpScanner(AntivirusProperties antivirus, Clock clock) {
        return new HttpAntivirusScanner(
                client(antivirus, antivirus.readTimeout()),
                client(antivirus, antivirus.healthTimeout()),
                antivirus.versionCacheTtl(),
                clock);
    }

    /** Rule B-7: connection AND read timeouts, both explicit. */
    private static RestClient client(AntivirusProperties antivirus, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(antivirus.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(antivirus.baseUrl().toString()).requestFactory(factory).build();
    }
}
