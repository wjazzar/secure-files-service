package com.praxedo.securefiles.infrastructure.web.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.TestIdentityProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Back-pressure: above the threshold of pending work, or with this node
 * already receiving as many uploads as it accepts, uploads are refused
 * politely — and before their body is read.
 *
 * <p>A separate class because the thresholds are lowered for these tests — a
 * different configuration is a different application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "praxedo.upload.max-pending-files=2",
        "praxedo.upload.max-concurrent-uploads=1",
        // Counted at every upload: these tests fill the queue and expect the very next upload to see it.
        "praxedo.upload.pending-count-max-age=0s"})
class UploadAdmissionTest extends FullStackTest {

    @Test
    @DisplayName("the third file while two wait: 429 with a delay, and nothing stored")
    void uploads_are_refused_above_the_threshold() {
        assertThat(upload("one.txt").status()).isEqualTo(202);
        assertThat(upload("two.txt").status()).isEqualTo(202);

        HttpApi.Response refused = upload("three.txt");

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("TOO_MANY_PENDING_FILES");
        assertThat(refused.header("Retry-After")).isPresent();
        assertThat(refused.json().path("retryAfterSeconds").asInt()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM stored_file").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ a client announcing Expect: 100-continue is refused before it sends its body")
    void a_refused_upload_never_receives_its_body() throws IOException {
        upload("one.txt");
        upload("two.txt");

        try (Socket client = announce(2 * 1024 * 1024)) {
            // No "100 Continue" first: the body is never asked for.
            assertThat(statusLine(client)).startsWith("HTTP/1.1 429");
        }
    }

    @Test
    @DisplayName("one upload in progress on a node that takes one: the next is refused at once")
    void a_full_node_refuses_at_once() throws IOException {
        try (Socket inProgress = announce(1024)) {
            // "100 Continue" comes only once the service reads the body: the place is taken.
            assertThat(statusLine(inProgress)).startsWith("HTTP/1.1 100");

            HttpApi.Response refused = upload("second.txt");

            assertThat(refused.status()).isEqualTo(429);
            assertThat(refused.code()).isEqualTo("TOO_MANY_CONCURRENT_UPLOADS");
            assertThat(refused.header("Retry-After")).contains("1");
        }
    }

    /** Headers only, as a careful client sends them: the body waits until the service asks for it. */
    private Socket announce(long length) throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(10_000);
        String headers = "POST /api/v1/files HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Authorization: Bearer " + TestIdentityProvider.accessToken() + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + length + "\r\n"
                + "X-File-Name: announced.bin\r\n"
                + "Expect: 100-continue\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private static String statusLine(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                .readLine();
    }

    private HttpApi.Response upload(String name) {
        return api.post("/api/v1/files", HttpRequest.BodyPublishers.ofString("content of " + name),
                "X-File-Name", name);
    }
}
