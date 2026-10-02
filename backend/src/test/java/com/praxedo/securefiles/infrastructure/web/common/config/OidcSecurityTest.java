package com.praxedo.securefiles.infrastructure.web.common.config;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jwt.JWTClaimsSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.service.FilePromotionService;
import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.ServableFiles;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.TestIdentityProvider;

import static com.praxedo.securefiles.testsupport.TestIdentityProvider.AUDIENCE;
import static com.praxedo.securefiles.testsupport.TestIdentityProvider.ISSUER;
import static com.praxedo.securefiles.testsupport.TestIdentityProvider.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API behind Keycloak, for third-party systems: a bearer access token.
 *
 * <p>The identity provider is simulated where it meets the service
 * ({@link TestIdentityProvider}): its published key set, served by WireMock,
 * and tokens signed with the matching private key. Everything on the service's
 * side is the production path: the key fetch, the signature, the issuer, the
 * audience, the expiry, and the owner taken from the subject.
 *
 * <p>The two properties that matter: a request without a valid token gets the
 * contract's {@code 401}, and two users never see each other's files — not in a
 * list, not by identifier, not by downloading.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcSecurityTest extends FullStackTest {

    @Autowired
    FileWorkQueue queue;

    @Autowired
    FilePromotionService promotion;

    /** Credentials are what these tests are about: each request brings its own, or none. */
    @BeforeEach
    void withoutDefaultCredentials() {
        api = new HttpApi(port);
    }

    @Nested
    @DisplayName("401 UNAUTHENTICATED, with a Bearer challenge")
    class Refused {

        @Test
        @DisplayName("no token")
        void no_token() {
            HttpApi.Response response = api.get("/api/v1/files");

            assertUnauthenticated(response);
            assertThat(response.header("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/problem+json"));
        }

        @Test
        @DisplayName("a token signed by someone else, under the right key identifier")
        void forged_signature() {
            assertUnauthenticated(api.get("/api/v1/files", bearer(forged("alice"))));
        }

        @Test
        @DisplayName("a token issued for another application")
        void wrong_audience() {
            assertUnauthenticated(api.get("/api/v1/files", bearer(accessToken("alice", ISSUER, "another-api", inAnHour()))));
        }

        @Test
        @DisplayName("a token from another issuer")
        void wrong_issuer() {
            assertUnauthenticated(api.get("/api/v1/files",
                    bearer(accessToken("alice", "http://elsewhere.test/realms/praxedo", AUDIENCE, inAnHour()))));
        }

        @Test
        @DisplayName("an expired token, beyond the one-minute clock tolerance")
        void expired() {
            assertUnauthenticated(api.get("/api/v1/files",
                    bearer(accessToken("alice", ISSUER, AUDIENCE, Instant.now().minusSeconds(120)))));
        }

        @Test
        @DisplayName("an upload, before a single byte of its body is stored")
        void upload() {
            HttpApi.Response response = api.post("/api/v1/files", HttpRequest.BodyPublishers.ofString("content"),
                    "X-File-Name", "a.txt");

            assertUnauthenticated(response);
            assertThat(jdbc.sql("SELECT count(*) FROM stored_file").query(Long.class).single()).isZero();
        }

        @Test
        @DisplayName("⭐ a download without credentials — there is no link to present instead: the caller's identity is the only way out")
        void download() {
            String alice = accessToken("alice");
            String content = "/api/v1/files/" + OidcSecurityTest.this.upload(alice) + "/content";
            new ServableFiles(queue, promotion).promoteNextDue();

            assertUnauthenticated(api.get(content));
            assertThat(api.get(content, bearer(alice)).status()).isEqualTo(200);
        }

        private void assertUnauthenticated(HttpApi.Response response) {
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.header("WWW-Authenticate")).hasValueSatisfying(
                    challenge -> assertThat(challenge).startsWith("Bearer"));
        }
    }

    @Nested
    @DisplayName("⭐ one private file space per user")
    class Partitioning {

        @Test
        @DisplayName("Bob sees nothing of Alice's: not in the list, not in the counters, not by identifier, not by downloading")
        void users_do_not_see_each_other() {
            String alice = accessToken("alice");
            String bob = accessToken("bob");
            String id = upload(alice);

            assertThat(api.get("/api/v1/files", bearer(alice)).json().path("page").path("totalElements").asLong()).isEqualTo(1);
            assertThat(api.get("/api/v1/files", bearer(bob)).json().path("page").path("totalElements").asLong()).isZero();
            assertThat(api.get("/api/v1/files/summary", bearer(alice)).json().path("total").asLong()).isEqualTo(1);
            assertThat(api.get("/api/v1/files/summary", bearer(bob)).json().path("total").asLong()).isZero();

            HttpApi.Response detail = api.get("/api/v1/files/" + id, bearer(bob));
            HttpApi.Response content = api.get("/api/v1/files/" + id + "/content", bearer(bob));
            for (HttpApi.Response refused : new HttpApi.Response[] {detail, content}) {
                assertThat(refused.status()).isEqualTo(404);
                assertThat(refused.code()).isEqualTo("FILE_NOT_FOUND");
            }
            assertThat(api.get("/api/v1/files/" + id, bearer(alice)).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("the owner is the token's subject, stored with the file")
        void the_owner_is_the_subject() {
            String subject = UUID.randomUUID().toString();
            String id = upload(accessToken(subject));

            assertThat(jdbc.sql("SELECT owner_id FROM stored_file WHERE id = :id")
                    .param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo(subject);
        }
    }

    @Nested
    @DisplayName("⭐ third-party systems are stateless: a bearer token, and nothing kept between two calls")
    class Stateless {

        @Test
        @DisplayName("no session created, no cookie set, no CSRF token asked — reads and writes alike")
        void no_session_no_cookie() {
            String alice = accessToken("alice");
            List<HttpApi.Response> calls = new ArrayList<>();

            calls.add(api.post("/api/v1/files", HttpRequest.BodyPublishers.fromPublisher(
                            HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(1_024)), 1_024),
                    "X-File-Name", "a.bin", "Content-Type", "application/octet-stream", "Authorization", "Bearer " + alice));
            calls.add(api.get("/api/v1/files", bearer(alice)));
            calls.add(api.get("/api/v1/auth/session", bearer(alice)));
            calls.add(api.get("/api/v1/files", bearer(forged("alice"))));

            assertThat(calls).extracting(HttpApi.Response::status).containsExactly(202, 200, 200, 401);
            assertThat(calls).allSatisfy(call -> assertThat(call.headers().allValues("Set-Cookie")).isEmpty());
            assertThat(jdbc.sql("SELECT count(*) FROM spring_session").query(Long.class).single()).isZero();
        }

        @Test
        @DisplayName("GET /auth/session describes the token's subject")
        void session_of_a_token() {
            HttpApi.Response session = api.get("/api/v1/auth/session", bearer(accessToken("alice-sub")));

            assertThat(session.json().path("id").asString()).isEqualTo("alice-sub");
            assertThat(session.json().path("username").asString()).isEqualTo("alice-sub");
        }
    }

    @Nested
    @DisplayName("what stays open")
    class Open {

        @Test
        @DisplayName("the resource metadata the 401 challenge points to answers without a token, and sets no cookie")
        void resource_metadata() {
            String challenge = api.get("/api/v1/files").header("WWW-Authenticate").orElseThrow();
            String metadata = challenge.replaceAll(".*resource_metadata=\"([^\"]+)\".*", "$1");

            HttpApi.Response response = api.get(metadata);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().path("resource").asString()).isNotBlank();
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }

        @Test
        @DisplayName("the health probes answer without credentials, on the management port")
        void probes() {
            assertThat(management.get("/actuator/health/readiness").status()).isEqualTo(200);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String upload(String token) {
        HttpApi.Response response = api.post("/api/v1/files",
                HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(4_096)), 4_096),
                "X-File-Name", "private.bin", "Content-Type", "application/octet-stream",
                "Authorization", "Bearer " + token);
        assertThat(response.status()).isEqualTo(202);
        return response.json().path("id").asString();
    }

    private static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }

    private static Instant inAnHour() {
        return Instant.now().plusSeconds(3600);
    }

    /** Every claim right, but signed by someone else under the genuine key's identifier. */
    private static String forged(String subject) {
        Instant expiresAt = inAnHour();
        return TestIdentityProvider.forge(new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(expiresAt.minusSeconds(3600)))
                .expirationTime(Date.from(expiresAt))
                .build());
    }
}
