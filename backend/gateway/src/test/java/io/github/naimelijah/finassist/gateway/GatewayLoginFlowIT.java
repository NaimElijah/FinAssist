package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.gateway.composition.support.FakeOpenIdProvider;
import io.github.naimelijah.finassist.gateway.composition.support.FakeService;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway's relay, proved end to end against a real, running gateway, a real (fake) OpenID
 * provider and a real (fake) downstream service — none of it mocked away — because the property
 * the gateway exists to guarantee is that an access token leaves this process only where it should
 * and never leaves it where it should not (ADR-030).
 *
 * <p>Every test drives the same code-plus-PKCE login a browser would, through a JDK {@link
 * HttpClient} carrying a {@link CookieManager}: hit the login entry, follow the redirect to the
 * fake provider, and carry the {@code code}/{@code state} pair it hands back to the gateway's own
 * callback — at the gateway's <em>real</em> test address rather than the {@code redirect_uri} the
 * {@code ClientRegistration} declares, {@code http://localhost:5173}, which nothing in this test
 * process is listening on. That mismatch is not a workaround; it proves, rather than assumes,
 * that Spring's {@code OAuth2AuthorizationCodeGrantRequestEntityConverter} and the
 * authorization-response matcher compare only the {@code state} parameter against the one stored
 * in the session-backed {@code AuthorizationRequest} repository — the redirect URI is never
 * compared at the callback, and every sign-in below completes regardless. A Vite dev proxy with
 * {@code changeOrigin: true} would produce exactly this mismatch, and sign-in still completes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "finassist.oidc.client-secret=a-test-client-secret")
@ExtendWith(OutputCaptureExtension.class)
class GatewayLoginFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String WEB_ORIGIN = "http://localhost:5173";
    private static final String SESSION_COOKIE = "FINASSIST_SESSION";

    /** A W3C trace id the client picks, so the value asserted downstream can only have come from it. */
    private static final String CLIENT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static FakeOpenIdProvider idp;
    private static FakeService ledger;
    private static FakeService assistant;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void startFakes() throws Exception {
        idp = new FakeOpenIdProvider();
        ledger = new FakeService();
        assistant = new FakeService();
    }

    @AfterAll
    static void stopFakes() {
        idp.close();
        ledger.close();
        assistant.close();
    }

    /**
     * Every property a relay or a login needs, pointed at the fakes above. Registered once,
     * before the context starts, because {@code finassist.relay.*} and {@code finassist.oidc.*}
     * are read by beans built at start-up.
     */
    @DynamicPropertySource
    static void relayAndOidcProperties(DynamicPropertyRegistry registry) {
        registry.add("finassist.oidc.public-url", idp::baseUrl);
        registry.add("finassist.oidc.internal-url", idp::baseUrl);
        registry.add("finassist.relay.ledger-uri", ledger::baseUrl);
        registry.add("finassist.relay.assistant-uri", assistant::baseUrl);
    }

    /**
     * Every test that mutates a fake's shared, mutable state resets it, because the fakes are
     * started once for the whole class and a later test must not inherit an earlier one's
     * configuration.
     */
    @AfterEach
    void resetFakes() {
        idp.setIssuerOverride(null);
        idp.setAccessTokenExpiresInSeconds(300);
        idp.stopRefusingRefresh();
        ledger.respondNext(200, "application/json", "{}");
        assistant.respondNext(200, "application/json", "{}");
    }

    private String gatewayUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    private HttpResponse<Void> getFollowingNothing(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> relay(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(gatewayUrl(path)))
                        .timeout(TIMEOUT)
                        // Sent even though this is a GET the CSRF filter would not otherwise
                        // check, so that "the header is stripped" has something real to strip.
                        .header("X-FINASSIST-XSRF-TOKEN", "a-header-that-must-not-reach-the-ledger")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Walks the browser's half of the login: the entry, the fake provider's authorization
     * endpoint, and the callback — sent to this test's real gateway address, which is the
     * redirect-URI mismatch described in the class Javadoc. Returns the callback's raw response so
     * a caller can assert either outcome: a completed sign-in, or a failed one.
     */
    private HttpResponse<Void> completeCallback(HttpClient client) throws Exception {
        return finishLogin(client, startLogin(client));
    }

    /**
     * The first half of a sign-in: the login entry, and the redirect it answers with. Split out so
     * a test can read what the browser holds <em>between</em> the two halves, which is the only
     * moment the pre-sign-in session cookie exists.
     */
    private URI startLogin(HttpClient client) throws Exception {
        HttpResponse<Void> entry = getFollowingNothing(client, gatewayUrl("/bff/oauth2/authorization/keycloak"));
        assertThat(entry.statusCode()).isEqualTo(302);
        URI authorizeUri = URI.create(entry.headers().firstValue("Location").orElseThrow());
        assertThat(queryParam(authorizeUri, "code_challenge_method")).isEqualTo("S256");
        assertThat(queryParam(authorizeUri, "code_challenge")).isNotBlank();
        return authorizeUri;
    }

    /** The second half: the fake provider's authorization endpoint, then the gateway's callback. */
    private HttpResponse<Void> finishLogin(HttpClient client, URI authorizeUri) throws Exception {
        HttpResponse<Void> authorized = getFollowingNothing(client, authorizeUri.toString());
        assertThat(authorized.statusCode()).isEqualTo(302);
        assertThat(idp.lastAuthorizationRequestHadPkce()).isTrue();
        URI codeRedirect =
                URI.create(authorized.headers().firstValue("Location").orElseThrow());
        assertThat(codeRedirect.toString()).startsWith(WEB_ORIGIN);

        URI realCallback =
                new URI("http", null, "localhost", port, codeRedirect.getPath(), codeRedirect.getQuery(), null);
        return getFollowingNothing(client, realCallback.toString());
    }

    /** A full, successful sign-in, for tests whose point is what happens afterward. */
    private void signIn(HttpClient client) throws Exception {
        HttpResponse<Void> callback = completeCallback(client);
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location")).contains(WEB_ORIGIN + "/");
        assertThat(idp.lastTokenRequestHadCodeVerifier()).isTrue();
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String sessionCookieValue(HttpClient client) {
        return cookieValue(client, SESSION_COOKIE);
    }

    /**
     * {@code csrf().spa()} resolves and writes the CSRF cookie eagerly on every request through
     * the security chain, so it is already in the cookie jar by the time {@link #signIn} returns
     * — no extra round trip is needed to fetch it before a write.
     */
    private static String csrfTokenValue(HttpClient client) {
        return cookieValue(client, "FINASSIST-XSRF-TOKEN");
    }

    private static String cookieValue(HttpClient client, String name) {
        CookieManager cookies = (CookieManager) client.cookieHandler().orElseThrow();
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie was ever set"));
    }

    @Test
    void Given_a_browser_signing_in_When_it_completes_the_code_flow_Then_pkce_is_sent_and_it_lands_on_the_public_url()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
        }
    }

    @Test
    void
            Given_a_signed_in_session_When_it_calls_the_ledger_route_Then_the_bearer_token_is_relayed_and_cookies_and_the_xsrf_header_and_prefix_are_stripped()
                    throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String accessToken = idp.lastAccessToken();

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(200);
            FakeService.ReceivedRequest received = ledger.lastRequest();
            assertThat(received.path()).isEqualTo("/api/v1/accounts");
            assertThat(received.header("authorization")).isEqualTo("Bearer " + accessToken);
            assertThat(received.hasHeader("cookie")).isFalse();
            assertThat(received.hasHeader("x-finassist-xsrf-token")).isFalse();
        }
    }

    /**
     * {@code Method=GET,POST} is a two-value predicate parsed from one comma-separated shortcut
     * argument; every other relay test in this class uses {@code GET} alone, so nothing else here
     * would notice if the {@code POST} half of that predicate quietly stopped matching.
     */
    @Test
    void Given_a_signed_in_session_When_it_posts_to_the_ledger_route_Then_the_method_predicate_matches_too()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String csrfToken = csrfTokenValue(client);

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(gatewayUrl("/ledger/api/v1/transactions")))
                            .timeout(TIMEOUT)
                            .header("X-FINASSIST-XSRF-TOKEN", csrfToken)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            FakeService.ReceivedRequest received = ledger.lastRequest();
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.path()).isEqualTo("/api/v1/transactions");
        }
    }

    /**
     * Session fixation, pinned rather than inherited. Spring's default authentication strategy is
     * {@code ChangeSessionIdAuthenticationStrategy}, and nothing in this gateway's configuration
     * names it — so only an assertion stops a future {@code sessionManagement} customiser from
     * quietly turning it off. A session id handed to the browser <em>before</em> a sign-in must
     * not still be valid after one, or a page that could plant that cookie would be signed in as
     * the owner the moment the owner signed in.
     */
    @Test
    void Given_a_session_started_before_sign_in_When_the_callback_completes_Then_the_session_id_changes()
            throws Exception {
        try (HttpClient client = newClient()) {
            URI authorizeUri = startLogin(client);
            String beforeSignIn = sessionCookieValue(client);

            HttpResponse<Void> callback = finishLogin(client, authorizeUri);
            assertThat(callback.statusCode()).isEqualTo(302);

            assertThat(sessionCookieValue(client)).isNotEqualTo(beforeSignIn);
        }
    }

    /**
     * The owner's rule is that a correlation id crosses every boundary, and the relay is one. The
     * ledger and the assistant each log a trace id, so a request a person is looking at must be
     * findable in all three services' logs under the same one.
     *
     * <p>The header is not passed through untouched: the gateway continues the trace, so the
     * {@code trace-id} field is the caller's while the {@code parent-id} field is the gateway's
     * own span. Both halves are asserted, because an implementation that simply copied the header
     * would satisfy the first alone and would record no span of its own.
     */
    @Test
    void Given_a_relayed_request_carrying_a_traceparent_When_it_arrives_Then_it_carries_the_same_trace_id()
            throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String traceparent = "00-" + CLIENT_TRACE_ID + "-00f067aa0ba902b7-01";

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(gatewayUrl("/ledger/api/v1/accounts")))
                            .timeout(TIMEOUT)
                            .header("traceparent", traceparent)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            String relayed = ledger.lastRequest().header("traceparent");
            assertThat(relayed).isNotNull();
            assertThat(relayed.split("-")[1]).isEqualTo(CLIENT_TRACE_ID);
            assertThat(relayed).isNotEqualTo(traceparent);
        }
    }

    @Test
    void Given_a_signed_in_session_When_it_calls_the_assistant_route_Then_it_is_relayed_too() throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            String accessToken = idp.lastAccessToken();

            HttpResponse<String> response = relay(client, "/assistant/api/v1/portfolio/holdings/ACME/position");

            assertThat(response.statusCode()).isEqualTo(200);
            FakeService.ReceivedRequest received = assistant.lastRequest();
            assertThat(received.path()).isEqualTo("/api/v1/portfolio/holdings/ACME/position");
            assertThat(received.header("authorization")).isEqualTo("Bearer " + accessToken);
        }
    }

    @Test
    void Given_the_ledger_answers_a_problem_When_relayed_Then_it_passes_through_unchanged() throws Exception {
        try (HttpClient client = newClient()) {
            signIn(client);
            ledger.respondNext(
                    422,
                    "application/problem+json",
                    "{\"type\":\"about:blank\",\"title\":\"Unprocessable Entity\",\"status\":422,"
                            + "\"detail\":\"the category does not exist\",\"code\":\"CATEGORY_NOT_FOUND\"}");

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.headers().firstValue("content-type")).contains("application/problem+json");
            assertThat(response.body()).contains("CATEGORY_NOT_FOUND");
        }
    }

    @Test
    void Given_an_already_expired_access_token_When_a_relay_is_made_Then_it_is_refreshed_before_the_call()
            throws Exception {
        try (HttpClient client = newClient()) {
            idp.setAccessTokenExpiresInSeconds(-120);
            signIn(client);
            String tokenIssuedAtLogin = idp.lastAccessToken();
            idp.setAccessTokenExpiresInSeconds(300);

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(200);
            String refreshedToken = idp.lastAccessToken();
            assertThat(refreshedToken).isNotEqualTo(tokenIssuedAtLogin);
            assertThat(ledger.lastRequest().header("authorization")).isEqualTo("Bearer " + refreshedToken);
        }
    }

    @Test
    void Given_a_refresh_that_keycloak_refuses_When_relayed_Then_the_session_is_invalidated_and_it_answers_401()
            throws Exception {
        try (HttpClient client = newClient()) {
            idp.setAccessTokenExpiresInSeconds(-120);
            signIn(client);
            idp.refuseNextRefresh();

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("UNAUTHENTICATED");

            HttpResponse<String> me = relay(client, "/bff/me");
            assertThat(me.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void
            Given_an_id_token_from_a_foreign_issuer_When_signing_in_Then_it_fails_and_the_browser_is_sent_to_signin_failed()
                    throws Exception {
        try (HttpClient client = newClient()) {
            idp.setIssuerOverride("http://foreign-issuer.example/realms/other");

            HttpResponse<Void> callback = completeCallback(client);

            assertThat(callback.statusCode()).isEqualTo(302);
            assertThat(callback.headers().firstValue("Location")).contains(WEB_ORIGIN + "/?signin=failed");
        }
    }

    /**
     * The end-to-end half of the guarantee {@code SignInFailureHandlerTest} already pins at the
     * unit level: nothing this flow logs carries a value that would let a reader impersonate the
     * session or redeem anything on the owner's behalf. A refused refresh is the highest-risk
     * moment in the whole relay, so it is the one this sweep deliberately drives through.
     */
    @Test
    void Given_a_login_and_a_refused_refresh_When_the_logs_are_swept_Then_no_token_code_or_session_value_appears(
            CapturedOutput output) throws Exception {
        try (HttpClient client = newClient()) {
            idp.setAccessTokenExpiresInSeconds(-120);
            idp.refuseNextRefresh();

            HttpResponse<Void> callback = completeCallback(client);
            assertThat(callback.statusCode()).isEqualTo(302);
            String accessToken = idp.lastAccessToken();
            String refreshToken = idp.lastRefreshToken();
            String idToken = idp.lastIdToken();
            String sessionCookie = sessionCookieValue(client);

            HttpResponse<String> response = relay(client, "/ledger/api/v1/accounts");
            assertThat(response.statusCode()).isEqualTo(401);

            String logs = output.getAll();
            assertThat(logs)
                    .doesNotContain(accessToken)
                    .doesNotContain(refreshToken)
                    .doesNotContain(idToken)
                    .doesNotContain(sessionCookie);
        }
    }

    /**
     * A dedicated context so that only this test's ledger URI is unreachable, leaving the outer
     * class's live {@code ledger} and {@code assistant} fakes untouched for every other test.
     */
    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "finassist.oidc.client-secret=a-test-client-secret")
    class LedgerDown {

        @LocalServerPort
        private int downPort;

        /**
         * A port reserved and immediately released: nothing listens on it for the life of this
         * nested context, so a connection to it is refused rather than merely slow.
         */
        @DynamicPropertySource
        static void deadLedger(DynamicPropertyRegistry registry) throws Exception {
            int deadPort;
            try (java.net.ServerSocket reserved = new java.net.ServerSocket(0)) {
                deadPort = reserved.getLocalPort();
            }
            registry.add("finassist.oidc.public-url", idp::baseUrl);
            registry.add("finassist.oidc.internal-url", idp::baseUrl);
            registry.add("finassist.relay.ledger-uri", () -> "http://localhost:" + deadPort);
            registry.add("finassist.relay.assistant-uri", assistant::baseUrl);
        }

        private String url(String path) {
            return "http://localhost:" + downPort + path;
        }

        @Test
        void Given_the_ledger_is_unreachable_When_relayed_Then_it_answers_502_upstream_unavailable() throws Exception {
            try (HttpClient client = newClient()) {
                HttpResponse<Void> entry = getFollowingNothing(client, url("/bff/oauth2/authorization/keycloak"));
                URI authorizeUri =
                        URI.create(entry.headers().firstValue("Location").orElseThrow());
                HttpResponse<Void> authorized = getFollowingNothing(client, authorizeUri.toString());
                URI codeRedirect =
                        URI.create(authorized.headers().firstValue("Location").orElseThrow());
                URI realCallback = new URI(
                        "http", null, "localhost", downPort, codeRedirect.getPath(), codeRedirect.getQuery(), null);
                HttpResponse<Void> callback = getFollowingNothing(client, realCallback.toString());
                assertThat(callback.statusCode()).isEqualTo(302);

                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url("/ledger/api/v1/accounts")))
                                .timeout(TIMEOUT)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(502);
                assertThat(response.body()).contains("UPSTREAM_UNAVAILABLE");
            }
        }
    }
}
