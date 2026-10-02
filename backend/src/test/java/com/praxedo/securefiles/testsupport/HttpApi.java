package com.praxedo.securefiles.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real HTTP client against the running application.
 *
 * <p>The web tests go through the network on purpose, not through a mocked
 * dispatcher: conditional requests, problem documents, streamed bodies and
 * header encodings are exactly the things a mock would get subtly right when
 * the real stack gets them wrong.
 */
public final class HttpApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final String[] credentials;

    /** Without credentials: every request brings its own, or none. */
    public HttpApi(int port) {
        this("http://localhost:" + port, new String[0]);
    }

    private HttpApi(String base, String[] credentials) {
        this.base = base;
        this.credentials = credentials;
    }

    /** The same API, called with {@code accessToken} as a bearer token on every request. */
    public HttpApi withToken(String accessToken) {
        return new HttpApi(base, new String[] {"Authorization", "Bearer " + accessToken});
    }

    public Response get(String path, String... headers) {
        return send(request(path, headers).GET().build());
    }

    public Response head(String path, String... headers) {
        return send(request(path, headers).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
    }

    public Response post(String path, HttpRequest.BodyPublisher body, String... headers) {
        return send(request(path, headers).POST(body).build());
    }

    /** For bodies that must not be read into memory — the download tests. */
    public HttpResponse<InputStream> getStream(String path, String... headers) {
        try {
            return client.send(request(path, headers).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public HttpResponse<InputStream> postForStream(String path, HttpRequest.BodyPublisher body, String... headers) {
        try {
            return client.send(request(path, headers).POST(body).build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path))
                .timeout(Duration.ofMinutes(5));
        for (int index = 0; index < credentials.length; index += 2) {
            builder.header(credentials[index], credentials[index + 1]);
        }
        for (int index = 0; index < headers.length; index += 2) {
            builder.header(headers[index], headers[index + 1]);
        }
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public record Response(int status, HttpHeaders headers, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }

        /** The contract's stable error code, for a problem document. */
        public String code() {
            return json().path("code").asString();
        }
    }
}
