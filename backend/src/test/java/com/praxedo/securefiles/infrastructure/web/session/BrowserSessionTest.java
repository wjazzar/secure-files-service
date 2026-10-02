package com.praxedo.securefiles.infrastructure.web.session;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jwt.JWTClaimsSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.service.FilePromotionService;
import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.ServableFiles;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.TestIdentityProvider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.praxedo.securefiles.testsupport.TestIdentityProvider.CLIENT_ID;
import static com.praxedo.securefiles.testsupport.TestIdentityProvider.CLIENT_SECRET;
import static com.praxedo.securefiles.testsupport.TestIdentityProvider.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser's way in (ADR-0012): Spring Security's OAuth2 client, the
 * service being Keycloak's confidential client, and a session kept by Spring
 * Session in PostgreSQL that the browser only knows as an {@code HttpOnly}
 * cookie.
 *
 * <p>Keycloak is simulated where it meets the service
 * ({@link TestIdentityProvider}) — its keys and its token endpoint, served by
 * WireMock — and the browser is played by hand, cookie by cookie, redirection
 * by redirection. Everything on the service's side is the production path.
 *
 * <p>The properties that matter: no token reaches the browser, and the client
 * secret never reaches the database; a sign-in cannot be completed in a
 * browser that did not start it; the session lives in the database, not in a
 * node; Keycloak's verdict is followed; and a cookie alone never authorises a
 * write.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Plain HTTP in the test, hence no __Host- prefix.
        "praxedo.security.cookies.secure=false"
})
class BrowserSessionTest extends FullStackTest {

    private static final String OIDC = TestIdentityProvider.OIDC_PATH;
    private static final String SESSION = "praxedo-session";
    /** An access token that expires at once — within Spring Security's one-minute clock skew. */
    private static final int EXPIRED_AT_ONCE = 1;
    private static final int FIVE_MINUTES = 300;

    private static final WireMockServer KEYCLOAK = TestIdentityProvider.server();

    @Autowired
    FileWorkQueue queue;

    @Autowired
    FilePromotionService promotion;

    /** The browser brings its cookies, nothing else: no default bearer token. */
    @BeforeEach
    void asABrowser() {
        api = new HttpApi(port);
        KEYCLOAK.resetRequests();
        KEYCLOAK.removeStubsByMetadata(matchingJsonPath("$.perTest"));
    }

    @Nested
    @DisplayName("signing in")
    class SigningIn {

        @Test
        @DisplayName("⭐ the redirection to Keycloak carries state, nonce and PKCE S256 — the verifier stays in the session")
        void the_redirection_to_keycloak() {
            HttpApi.Response login = api.get("/api/v1/auth/login?redirect=%2Ffiles");

            assertThat(login.status()).isEqualTo(302);
            URI location = URI.create(login.header("Location").orElseThrow());
            Map<String, String> query = query(location);
            assertThat(location.toString()).startsWith(ISSUER + "/protocol/openid-connect/auth?");
            assertThat(query).containsEntry("response_type", "code")
                    .containsEntry("client_id", CLIENT_ID)
                    .containsEntry("code_challenge_method", "S256")
                    .containsKeys("state", "nonce", "code_challenge")
                    .doesNotContainKeys("client_secret", "code_verifier");
            assertThat(query.get("scope")).contains("openid");
            assertThat(query.get("redirect_uri")).isEqualTo("http://localhost:" + port + "/api/v1/auth/callback");

            assertThat(setCookie(login, SESSION)).hasValueSatisfying(cookie ->
                    assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Max-Age"));
            assertThat(sessionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⭐ the return opens the session under a new identifier: the one from before signing in opens nothing")
        void the_return_opens_a_session_under_a_new_identifier() {
            Browser browser = signIn("alice-sub", "/files?status=AVAILABLE", FIVE_MINUTES);

            assertThat(browser.callback.status()).isEqualTo(302);
            assertThat(browser.callback.header("Location")).contains("/files?status=AVAILABLE");
            assertThat(setCookie(browser.callback, SESSION)).hasValueSatisfying(cookie ->
                    assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Max-Age"));
            assertThat(browser.session).isNotEqualTo(browser.beforeSignIn);

            assertThat(jdbc.sql("SELECT principal_name FROM spring_session").query(String.class).single())
                    .isEqualTo("alice-sub");
            assertThat(api.get("/api/v1/auth/session", "Cookie", SESSION + "=" + browser.beforeSignIn).status())
                    .isEqualTo(401);
            assertThat(api.get("/api/v1/auth/session", "Cookie", browser.cookies()).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("⭐ the code is redeemed with the client secret and the PKCE verifier that matches the challenge")
        void the_code_exchange() throws Exception {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);

            List<LoggedRequest> exchanges = KEYCLOAK.findAll(postRequestedFor(urlEqualTo(OIDC + "/token")));
            assertThat(exchanges).hasSize(1);
            LoggedRequest exchange = exchanges.getFirst();
            assertThat(exchange.getHeader("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
                    .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)));
            Map<String, String> form = form(exchange.getBodyAsString());
            assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "code-alice-sub");
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
            assertThat(challenge).isEqualTo(browser.authorization.get("code_challenge"));
        }

        @Test
        @DisplayName("⭐ no token reaches the browser, and the client secret never reaches the database")
        void nothing_usable_where_it_does_not_belong() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);

            String seenByTheBrowser = browser.callback.headers().map() + browser.callback.body()
                    + api.get("/api/v1/auth/session", "Cookie", browser.cookies()).body();
            assertThat(seenByTheBrowser).doesNotContain("rt-alice-sub").doesNotContain("opaque-access-token")
                    .doesNotContain(browser.idToken);

            assertThat(storedSession()).contains("rt-alice-sub").doesNotContain(CLIENT_SECRET);
        }

        @Test
        @DisplayName("⭐ a return in a browser that did not start the sign-in fails (login CSRF)")
        void a_return_in_another_browser_fails() {
            Map<String, String> authorization = query(URI.create(
                    api.get("/api/v1/auth/login").header("Location").orElseThrow()));

            HttpApi.Response callback = api.get("/api/v1/auth/callback?code=code-x&state=" + authorization.get("state"));

            assertSignInFailed(callback);
        }

        @Test
        @DisplayName("a return with another state fails")
        void a_return_with_another_state_fails() {
            HttpApi.Response login = api.get("/api/v1/auth/login");

            assertSignInFailed(api.get("/api/v1/auth/callback?code=c&state=another",
                    "Cookie", SESSION + "=" + cookieValue(setCookie(login, SESSION).orElseThrow())));
        }

        @Test
        @DisplayName("⭐ an identity token issued for another sign-in (wrong nonce) is refused")
        void an_identity_token_with_another_nonce_is_refused() {
            HttpApi.Response login = api.get("/api/v1/auth/login");
            Map<String, String> authorization = query(URI.create(login.header("Location").orElseThrow()));
            stubCodeExchange("mallory", "another-nonce", FIVE_MINUTES);

            HttpApi.Response callback = api.get("/api/v1/auth/callback?code=code-mallory&state=" + authorization.get("state"),
                    "Cookie", SESSION + "=" + cookieValue(setCookie(login, SESSION).orElseThrow()));

            assertSignInFailed(callback);
        }

        @Test
        @DisplayName("⭐ no open redirection: a foreign target sends the browser home")
        void no_open_redirection() {
            Browser browser = signIn("alice-sub", "https://evil.test/phish", FIVE_MINUTES);

            assertThat(browser.callback.header("Location")).hasValueSatisfying(location ->
                    assertThat(URI.create(location).getPath()).isEqualTo("/"));
        }

        private void assertSignInFailed(HttpApi.Response callback) {
            assertThat(callback.status()).isEqualTo(302);
            assertThat(callback.header("Location")).hasValueSatisfying(location ->
                    assertThat(location).endsWith("/login?error=sign-in-failed"));
            assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")
                    .query(Long.class).single()).isZero();
        }
    }

    @Nested
    @DisplayName("using the session")
    class UsingTheSession {

        @Test
        @DisplayName("GET /auth/session says who is signed in — and issues the CSRF cookie")
        void who_is_signed_in() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);

            HttpApi.Response session = api.get("/api/v1/auth/session", "Cookie", browser.cookies());

            assertThat(session.status()).isEqualTo(200);
            assertThat(session.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
            assertThat(session.json().path("id").asString()).isEqualTo("alice-sub");
            assertThat(session.json().path("username").asString()).isEqualTo("alice");
            assertThat(session.json().path("displayName").asString()).isEqualTo("Alice Demo");
            assertThat(session.json().path("email").asString()).isEqualTo("alice@example.test");
            assertThat(setCookie(session, "XSRF-TOKEN")).hasValueSatisfying(cookie ->
                    assertThat(cookie).doesNotContain("HttpOnly").contains("SameSite=Strict"));
        }

        @Test
        @DisplayName("without a session, or with an unknown one: 401 UNAUTHENTICATED")
        void no_session() {
            HttpApi.Response none = api.get("/api/v1/auth/session");
            HttpApi.Response unknown = api.get("/api/v1/auth/session", "Cookie", SESSION + "="
                    + Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)));

            assertThat(none.status()).isEqualTo(401);
            assertThat(none.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(unknown.status()).isEqualTo(401);
        }

        @Test
        @DisplayName("⭐ the session lives in the database, not in a node: removed there, it opens nothing here")
        void the_session_lives_in_the_database() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);

            jdbc.sql("DELETE FROM spring_session").update();

            assertThat(api.get("/api/v1/files", "Cookie", browser.cookies()).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("⭐ a write with the session cookie but without the CSRF token: 403, before a single byte is stored")
        void a_cookie_alone_never_authorises_a_write() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);

            HttpApi.Response forged = upload("Cookie", browser.cookies());

            assertThat(forged.status()).isEqualTo(403);
            assertThat(forged.code()).isEqualTo("CSRF_TOKEN_INVALID");
            assertThat(jdbc.sql("SELECT count(*) FROM stored_file").query(Long.class).single()).isZero();
        }

        @Test
        @DisplayName("⭐ with the echoed token it goes through, and the file belongs to the signed-in subject")
        void the_owner_is_the_subject_of_the_identity_token() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);
            String csrf = browser.csrf();

            HttpApi.Response uploaded = upload("Cookie", browser.cookies(), "X-XSRF-TOKEN", csrf);

            assertThat(uploaded.status()).isEqualTo(202);
            assertThat(jdbc.sql("SELECT owner_id FROM stored_file").query(String.class).single()).isEqualTo("alice-sub");
            assertThat(api.get("/api/v1/files", "Cookie", browser.cookies()).json()
                    .path("page").path("totalElements").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("⭐ a download is a plain navigation: the session cookie alone opens the content — no link, no second credential")
        void a_download_needs_nothing_but_the_session() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);
            String csrf = browser.csrf();
            String id = upload("Cookie", browser.cookies(), "X-XSRF-TOKEN", csrf).json().path("id").asString();
            new ServableFiles(queue, promotion).promoteNextDue();
            String content = "/api/v1/files/" + id + "/content";

            HttpApi.Response download = api.get(content, "Cookie", browser.cookies());

            assertThat(download.status()).isEqualTo(200);
            assertThat(download.header("Content-Disposition")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("attachment;"));
            assertThat(download.header("Content-Length")).contains("1024");
            assertThat(api.get(content).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("a write without a session still gets the contract's 401, not a 403")
        void writes_without_a_session_get_401() {
            assertThat(upload().status()).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("Keycloak stays the authority")
    class Revalidation {

        @Test
        @DisplayName("⭐ past the access token's lifetime, the tokens are renewed with the client secret — once, for every node")
        void renewed() {
            Browser browser = signIn("alice-sub", "/", EXPIRED_AT_ONCE);
            stubRefresh(okJson(tokenResponse(null, "rt-alice-2", FIVE_MINUTES)));

            assertThat(api.get("/api/v1/files", "Cookie", browser.cookies()).status()).isEqualTo(200);
            assertThat(api.get("/api/v1/files", "Cookie", browser.cookies()).status()).isEqualTo(200);

            List<LoggedRequest> refreshes = refreshes();
            assertThat(refreshes).hasSize(1);
            assertThat(form(refreshes.getFirst().getBodyAsString())).containsEntry("refresh_token", "rt-alice-sub");
            assertThat(refreshes.getFirst().getHeader("Authorization")).startsWith("Basic ");
            // Written back to the shared session: whichever node serves the next request reads these.
            assertThat(storedSession()).contains("rt-alice-2").doesNotContain("rt-alice-sub");
        }

        @Test
        @DisplayName("⭐ a session ended in Keycloak is over here at the next revalidation")
        void rejected() {
            Browser browser = signIn("alice-sub", "/", EXPIRED_AT_ONCE);
            stubRefresh(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}"));

            HttpApi.Response response = api.get("/api/v1/files", "Cookie", browser.cookies());

            assertThat(response.status()).isEqualTo(401);
            assertThat(setCookie(response, SESSION)).hasValueSatisfying(cleared -> assertThat(cleared).contains("Max-Age=0"));
            assertThat(sessionCount()).isZero();
        }

        @Test
        @DisplayName("⭐ Keycloak down: the signed-in user keeps working, and Keycloak is not asked on every request")
        void unavailable() {
            Browser browser = signIn("alice-sub", "/", EXPIRED_AT_ONCE);
            stubRefresh(aResponse().withStatus(503));

            assertThat(api.get("/api/v1/files", "Cookie", browser.cookies()).status()).isEqualTo(200);
            assertThat(api.get("/api/v1/files", "Cookie", browser.cookies()).status()).isEqualTo(200);

            assertThat(refreshes()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("signing out")
    class SigningOut {

        @Test
        @DisplayName("⭐ closes the session here, and hands the browser Keycloak's end-session address")
        void here_then_at_keycloak() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);
            String csrf = browser.csrf();

            HttpApi.Response logout = api.post("/api/v1/auth/logout", HttpRequest.BodyPublishers.noBody(),
                    "Cookie", browser.cookies(), "X-XSRF-TOKEN", csrf);

            assertThat(logout.status()).isEqualTo(200);
            assertThat(logout.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
            URI endSession = URI.create(logout.json().path("logoutUrl").asString());
            assertThat(endSession.toString()).startsWith(ISSUER + "/protocol/openid-connect/logout?");
            assertThat(query(endSession)).containsEntry("id_token_hint", browser.idToken)
                    .containsEntry("post_logout_redirect_uri", "http://localhost:" + port + "/login?signed-out");

            assertThat(setCookie(logout, SESSION)).hasValueSatisfying(cleared -> assertThat(cleared).contains("Max-Age=0"));
            assertThat(sessionCount()).isZero();
            assertThat(api.get("/api/v1/auth/session", "Cookie", browser.cookies()).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("⭐ another site cannot sign the user out: without the CSRF token, 403 and the session stays")
        void not_without_the_csrf_token() {
            Browser browser = signIn("alice-sub", "/", FIVE_MINUTES);

            HttpApi.Response forged = api.post("/api/v1/auth/logout", HttpRequest.BodyPublishers.noBody(),
                    "Cookie", browser.cookies());

            assertThat(forged.status()).isEqualTo(403);
            assertThat(forged.code()).isEqualTo("CSRF_TOKEN_INVALID");
            assertThat(api.get("/api/v1/auth/session", "Cookie", browser.cookies()).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("signing out without a session is not an error: straight to the login page")
        void without_a_session() {
            HttpApi.Response logout = api.post("/api/v1/auth/logout", HttpRequest.BodyPublishers.noBody());

            assertThat(logout.status()).isEqualTo(200);
            assertThat(logout.json().path("logoutUrl").asString()).isEqualTo("/login?signed-out");
        }
    }

    // ── the browser, played by hand ─────────────────────────────────────

    /** What a browser holds after signing in. */
    private final class Browser {

        final Map<String, String> authorization;
        final HttpApi.Response callback;
        final String beforeSignIn;
        final String session;
        final String idToken;
        private String xsrf;

        Browser(Map<String, String> authorization, String beforeSignIn, HttpApi.Response callback, String idToken) {
            this.authorization = authorization;
            this.beforeSignIn = beforeSignIn;
            this.callback = callback;
            this.session = setCookie(callback, SESSION).map(BrowserSessionTest::cookieValue).orElse(null);
            this.idToken = idToken;
        }

        String cookies() {
            return SESSION + "=" + session + (xsrf == null ? "" : "; XSRF-TOKEN=" + xsrf);
        }

        /** As the interface does: read the session first, which issues the CSRF cookie, then echo it. */
        String csrf() {
            HttpApi.Response response = api.get("/api/v1/auth/session", "Cookie", cookies());
            xsrf = setCookie(response, "XSRF-TOKEN").map(BrowserSessionTest::cookieValue).orElseThrow();
            return xsrf;
        }
    }

    private Browser signIn(String subject, String returnTo, int accessTokenLifetime) {
        HttpApi.Response login = api.get("/api/v1/auth/login?redirect="
                + URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
        Map<String, String> authorization = query(URI.create(login.header("Location").orElseThrow()));
        String beforeSignIn = cookieValue(setCookie(login, SESSION).orElseThrow());
        String idToken = stubCodeExchange(subject, authorization.get("nonce"), accessTokenLifetime);
        // Keycloak authenticates the user, then sends the browser back — with its session cookie.
        HttpApi.Response callback = api.get(
                "/api/v1/auth/callback?code=code-" + subject + "&state=" + authorization.get("state"),
                "Cookie", SESSION + "=" + beforeSignIn);
        return new Browser(authorization, beforeSignIn, callback, idToken);
    }

    private String stubCodeExchange(String subject, String nonce, int accessTokenLifetime) {
        String idToken = idToken(subject, nonce);
        KEYCLOAK.stubFor(post(urlEqualTo(OIDC + "/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=code-" + subject))
                .withMetadata(perTest())
                .willReturn(okJson(tokenResponse(idToken, "rt-" + subject, accessTokenLifetime))));
        return idToken;
    }

    private void stubRefresh(ResponseDefinitionBuilder answer) {
        KEYCLOAK.stubFor(post(urlEqualTo(OIDC + "/token")).withRequestBody(containing("grant_type=refresh_token"))
                .withMetadata(perTest())
                .willReturn(answer));
    }

    private List<LoggedRequest> refreshes() {
        return KEYCLOAK.findAll(postRequestedFor(urlEqualTo(OIDC + "/token"))
                .withRequestBody(containing("grant_type=refresh_token")));
    }

    private long sessionCount() {
        return jdbc.sql("SELECT count(*) FROM spring_session").query(Long.class).single();
    }

    /** Every attribute of every stored session, as the bytes a database dump would show. */
    private String storedSession() {
        return String.join("|", jdbc.sql("SELECT attribute_bytes FROM spring_session_attributes")
                .query((row, index) -> new String(row.getBytes(1), StandardCharsets.ISO_8859_1)).list());
    }

    private HttpApi.Response upload(String... headers) {
        String[] all = new String[headers.length + 4];
        all[0] = "X-File-Name";
        all[1] = "note.txt";
        all[2] = "Content-Type";
        all[3] = "application/octet-stream";
        System.arraycopy(headers, 0, all, 4, headers.length);
        return api.post("/api/v1/files", HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(1_024)), 1_024), all);
    }

    private static String tokenResponse(String idToken, String refreshToken, int expiresIn) {
        return "{\"access_token\":\"opaque-access-token\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + ","
                + "\"refresh_token\":\"" + refreshToken + "\",\"refresh_expires_in\":1800,"
                + "\"scope\":\"openid profile email\""
                + (idToken == null ? "" : ",\"id_token\":\"" + idToken + "\"") + "}";
    }

    private static String idToken(String subject, String nonce) {
        Instant now = Instant.now();
        return TestIdentityProvider.sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(CLIENT_ID)
                .claim("azp", CLIENT_ID)
                .claim("nonce", nonce)
                .claim("preferred_username", subject.replace("-sub", ""))
                .claim("name", "Alice Demo")
                .claim("email", "alice@example.test")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build());
    }

    private static Map<String, Object> perTest() {
        return Map.of("perTest", true);
    }

    private static Optional<String> setCookie(HttpApi.Response response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .reduce((first, last) -> last);
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private static Map<String, String> query(URI uri) {
        return form(uri.getRawQuery());
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

}
