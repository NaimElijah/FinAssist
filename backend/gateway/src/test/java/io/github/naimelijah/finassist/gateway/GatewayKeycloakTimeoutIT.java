package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.gateway.composition.support.FakeOpenIdProvider;
import io.github.naimelijah.finassist.gateway.composition.support.FakeService;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every call the gateway makes to Keycloak is bounded, proved against a provider that accepts the
 * connection and then never answers.
 *
 * <p>That failure — a paused container, a long garbage-collection pause, a lock held in
 * {@code keycloak-db} — is the one an unbounded client cannot survive: the TCP handshake
 * completes, so no connect timeout fires, and the client waits for a first byte for ever, holding
 * a Tomcat thread while the browser spins with no error. A refused connection, which
 * {@code GatewayLoginFlowIT.UpstreamsDown} already covers for a relay, fails immediately and proves
 * nothing about a timeout.
 *
 * <p>Three calls leave this gateway for Keycloak, and each is exercised below:
 *
 * <ul>
 *   <li>the authorization-code exchange at the callback;
 *   <li>the {@code refresh_token} grant a relay makes when the access token has expired;
 *   <li>the JWK-set fetch that verifies the ID token's signature at sign-in.
 * </ul>
 *
 * <p>None of them is the relay's own {@code RestClient}, which Spring Cloud Gateway builds from
 * Boot's auto-configured builder and which has therefore always been bounded; {@code
 * GatewayRelayTimeoutIT} stalls that one. Spring Security's
 * token clients and {@code OidcIdTokenDecoderFactory} each build their own HTTP client from a
 * static factory instead, so each has to be given Boot's budget deliberately —
 * {@code KeycloakRegistration} is where that is done.
 *
 * <p><b>How this stays deterministic.</b> Nothing sleeps: the fake's stalled handler waits on a
 * latch this test releases after each case. The budget is cut to {@link #BUDGET} for this context
 * alone, and every assertion is that the gateway answered within {@link #ANSWER_LIMIT} — many
 * times the budget, and a small fraction of the two minutes the fake would stall for. Each request
 * also carries its own {@link #ANSWER_LIMIT} timeout, so an unbounded gateway fails this test with
 * a {@code HttpTimeoutException} rather than hanging the build.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finassist.oidc.client-secret=a-test-client-secret",
            // WEB_ORIGIN, the plain-HTTP cookie jar and the localhost Host header depend on these,
            // which application.yml would otherwise take from the process environment.
            "finassist.web.public-url=http://localhost:5173",
            "finassist.http.allowed-hosts=localhost,127.0.0.1",
            "finassist.http.cookie-secure=false",
            "spring.http.clients.connect-timeout=" + GatewayKeycloakTimeoutIT.BUDGET,
            "spring.http.clients.read-timeout=" + GatewayKeycloakTimeoutIT.BUDGET
        })
class GatewayKeycloakTimeoutIT {

    /** The outbound budget this context runs with, short so a bounded call fails quickly. */
    static final String BUDGET = "1s";

    /** How long the gateway may take to answer once Keycloak has stopped answering it. */
    private static final Duration ANSWER_LIMIT = Duration.ofSeconds(20);

    /** What a passing run must beat: far above {@link #BUDGET}, far below the fake's stall limit. */
    private static final Duration ELAPSED_LIMIT = Duration.ofSeconds(15);

    private static final String WEB_ORIGIN = "http://localhost:5173";
    private static final String SIGN_IN_FAILED = WEB_ORIGIN + "/?signin=failed";

    private static FakeOpenIdProvider idp;
    private static FakeService ledger;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void startFakes() throws Exception {
        idp = new FakeOpenIdProvider();
        ledger = new FakeService();
    }

    @AfterAll
    static void stopFakes() {
        idp.close();
        ledger.close();
    }

    @DynamicPropertySource
    static void relayAndOidcProperties(DynamicPropertyRegistry registry) {
        registry.add("finassist.oidc.public-url", idp::baseUrl);
        registry.add("finassist.oidc.internal-url", idp::baseUrl);
        registry.add("finassist.relay.ledger-uri", ledger::baseUrl);
        registry.add("finassist.relay.assistant-uri", ledger::baseUrl);
    }

    /**
     * Lets every stalled handler finish before the next test runs. Without it a stall would
     * outlive the case that set it, and the thread waiting on the latch would stay parked for the
     * fake's whole stall limit.
     */
    @AfterEach
    void releaseStalls() {
        idp.releaseStalledRequests();
        idp.setAccessTokenExpiresInSeconds(300);
    }

    private String gatewayUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(ANSWER_LIMIT)
                .build();
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(ANSWER_LIMIT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Drives the browser's half of a sign-in and returns the callback's answer. The callback is
     * sent to this test's real gateway address rather than the registration's {@code redirect_uri},
     * exactly as {@code GatewayLoginFlowIT} explains at length.
     */
    private HttpResponse<String> completeCallback(HttpClient client) throws Exception {
        HttpResponse<String> entry = get(client, gatewayUrl("/bff/oauth2/authorization/keycloak"));
        assertThat(entry.statusCode()).isEqualTo(302);
        URI authorizeUri = URI.create(entry.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> authorized = get(client, authorizeUri.toString());
        assertThat(authorized.statusCode()).isEqualTo(302);
        URI codeRedirect =
                URI.create(authorized.headers().firstValue("Location").orElseThrow());

        URI realCallback =
                new URI("http", null, "localhost", port, codeRedirect.getPath(), codeRedirect.getQuery(), null);
        return get(client, realCallback.toString());
    }

    /** Asserts that {@code work} both produced the expected answer and did so inside the budget. */
    private static void within(Duration limit, ThrowingRunnable work) throws Exception {
        long startedAt = System.nanoTime();
        work.run();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(elapsed)
                .withFailMessage("the gateway took %s, so the call to Keycloak was not bounded by %s", elapsed, BUDGET)
                .isLessThan(limit);
    }

    /** A body of work that may throw, so a test's assertions can be timed as one block. */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void Given_keycloaks_token_endpoint_never_answers_When_a_sign_in_is_completed_Then_the_exchange_gives_up()
            throws Exception {
        try (HttpClient client = newClient()) {
            idp.stall(FakeOpenIdProvider.Endpoint.TOKEN);

            within(ELAPSED_LIMIT, () -> {
                HttpResponse<String> callback = completeCallback(client);

                assertThat(callback.statusCode()).isEqualTo(302);
                assertThat(callback.headers().firstValue("Location")).contains(SIGN_IN_FAILED);
            });
            assertStallReached(FakeOpenIdProvider.Endpoint.TOKEN);
        }
    }

    @Test
    void Given_keycloaks_jwk_set_never_answers_When_a_sign_in_is_completed_Then_the_id_token_check_gives_up()
            throws Exception {
        try (HttpClient client = newClient()) {
            idp.stall(FakeOpenIdProvider.Endpoint.CERTS);

            within(ELAPSED_LIMIT, () -> {
                HttpResponse<String> callback = completeCallback(client);

                assertThat(callback.statusCode()).isEqualTo(302);
                assertThat(callback.headers().firstValue("Location")).contains(SIGN_IN_FAILED);
            });
            assertStallReached(FakeOpenIdProvider.Endpoint.CERTS);
        }
    }

    @Test
    void Given_keycloak_stops_answering_after_sign_in_When_a_relay_must_refresh_Then_the_refresh_gives_up()
            throws Exception {
        try (HttpClient client = newClient()) {
            // Already expired when issued, so the very next relay has to redeem the refresh token.
            idp.setAccessTokenExpiresInSeconds(-120);
            HttpResponse<String> callback = completeCallback(client);
            assertThat(callback.statusCode()).isEqualTo(302);
            assertThat(callback.headers().firstValue("Location")).contains(WEB_ORIGIN + "/");

            idp.stall(FakeOpenIdProvider.Endpoint.TOKEN);

            within(ELAPSED_LIMIT, () -> {
                HttpResponse<String> relayed = get(client, gatewayUrl("/ledger/api/v1/accounts"));

                // Fail closed: a refresh that could not be made invalidates the session, exactly
                // as a refused one does (GatewayRelayProblems).
                assertThat(relayed.statusCode()).isEqualTo(401);
                assertThat(relayed.body()).contains("UNAUTHENTICATED");
            });
            assertStallReached(FakeOpenIdProvider.Endpoint.TOKEN);
        }
    }

    /**
     * The call this case meant to strand really arrived. Without it a flow that failed fast for some
     * other reason — a refused exchange, say — would answer inside the limit just the same, and the
     * case would pass without the budget ever being tested.
     */
    private static void assertStallReached(FakeOpenIdProvider.Endpoint endpoint) {
        assertThat(idp.stalledArrivals(endpoint))
                .as("requests that reached the stalled %s endpoint", endpoint)
                .isPositive();
    }
}
