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
 * A relayed request to an upstream that accepts it and then never answers is given up on, and
 * answered {@code 502 UPSTREAM_UNAVAILABLE}, rather than holding a Tomcat thread for ever.
 *
 * <p>{@code GatewayLoginFlowIT.UpstreamsDown} covers an upstream that refuses the connection, which
 * fails at once and says nothing about a read timeout. {@code GatewayApplicationIT} pins the
 * shipped budget's numbers; this proves the relay is held to the budget configured, not merely to
 * some budget: {@link #ELAPSED_LIMIT} sits below the shipped ten-second read timeout, so a relay
 * that ignored this context's override would fail it.
 *
 * <p><b>How this stays deterministic.</b> Nothing sleeps: the fake ledger's stalled handler waits on
 * a latch this test releases after each case, and is otherwise abandoned after two minutes. The
 * budget is {@link #BUDGET} for this context alone. That is long enough that the sign-in, whose calls
 * to the fake provider share it, does not time out on a loaded machine (a one-second budget did,
 * now and then), and short enough that {@link #ELAPSED_LIMIT} leaves it more than twice its own
 * length of headroom while staying under the shipped budget, and a small fraction of the stall.
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
            "spring.http.clients.connect-timeout=" + GatewayRelayTimeoutIT.BUDGET,
            "spring.http.clients.read-timeout=" + GatewayRelayTimeoutIT.BUDGET
        })
class GatewayRelayTimeoutIT {

    /** The outbound budget this context runs with. */
    static final String BUDGET = "3s";

    /** How long a request to the gateway may take before the client itself gives up. */
    private static final Duration ANSWER_LIMIT = Duration.ofSeconds(30);

    /**
     * What a passing relay must beat: well above {@link #BUDGET}, but below the shipped {@code
     * read-timeout} of 10 s in {@code application.yml}, so a relay that kept the shipped budget
     * and ignored this context's fails rather than passing on a generous limit.
     */
    private static final Duration ELAPSED_LIMIT = Duration.ofSeconds(8);

    private static final String WEB_ORIGIN = "http://localhost:5173";

    private static FakeOpenIdProvider idp;
    private static FakeService ledger;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void startFakes() throws Exception {
        idp = new FakeOpenIdProvider("a-test-client-secret");
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

    /** Lets the stalled exchange finish, so its thread is not left parked for the fake's whole limit. */
    @AfterEach
    void releaseStalls() {
        ledger.releaseStalledRequests();
    }

    private String gatewayUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(ANSWER_LIMIT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The browser's half of a sign-in, with the callback sent to this test's own gateway address. */
    private void signIn(HttpClient client) throws Exception {
        HttpResponse<String> entry = get(client, gatewayUrl("/bff/oauth2/authorization/keycloak"));
        assertThat(entry.statusCode()).isEqualTo(302);
        URI authorizeUri = URI.create(entry.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> authorized = get(client, authorizeUri.toString());
        URI codeRedirect =
                URI.create(authorized.headers().firstValue("Location").orElseThrow());
        URI realCallback =
                new URI("http", null, "localhost", port, codeRedirect.getPath(), codeRedirect.getQuery(), null);

        HttpResponse<String> callback = get(client, realCallback.toString());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location")).contains(WEB_ORIGIN + "/");
    }

    @Test
    void Given_the_ledger_accepts_a_relayed_request_and_never_answers_When_relayed_Then_the_relay_gives_up_with_502()
            throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(ANSWER_LIMIT)
                .build()) {
            signIn(client);
            ledger.stall();

            long startedAt = System.nanoTime();
            HttpResponse<String> relayed = get(client, gatewayUrl("/ledger/api/v1/accounts"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(ledger.stalledArrivals())
                    .as("relayed requests that reached the stalled ledger")
                    .isPositive();
            assertThat(elapsed)
                    .withFailMessage("the relay took %s, so it was not bounded by %s", elapsed, BUDGET)
                    .isLessThan(ELAPSED_LIMIT);
            assertThat(relayed.statusCode()).isEqualTo(502);
            assertThat(relayed.headers().firstValue("content-type")).contains("application/problem+json");
            assertThat(relayed.body()).contains("\"code\":\"UPSTREAM_UNAVAILABLE\"");
        }
    }
}
