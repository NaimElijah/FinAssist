package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The gateway as a running server: the one claim this task makes is that it boots and answers
 * its own health probe, the same way {@code LedgerApplicationIT} proves that for the ledger.
 *
 * <p>Nothing here exercises a route, OIDC or a session, because none of them exist yet - see
 * {@code GatewayApplication}'s Javadoc. This is the smoke test a later task's routing and
 * security work builds on, not a stand-in for it.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than {@code TestRestTemplate}, which Spring Boot 4
 * moved out of {@code spring-boot-starter-test} into a separate artefact the ledger also declined
 * to add for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    /**
     * One client for the class, closed afterwards. {@link HttpClient} owns a selector thread and
     * an executor and is {@link AutoCloseable} on Java 21, so building one per request would leave
     * it running until the JVM exited.
     */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void Given_the_gateway_When_it_boots_Then_health_reports_up() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
