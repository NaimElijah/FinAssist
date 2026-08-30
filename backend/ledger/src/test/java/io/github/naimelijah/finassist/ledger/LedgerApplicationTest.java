package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The only test that boots Spring, and the only one that touches HTTP.
 *
 * <p>Everything else in this module is a pure domain test, because everything else in this module
 * is pure domain. This exists because the README and the architecture document both claim the
 * ledger "boots and answers Actuator probes", and until now nothing checked either half of that
 * sentence. A claim in a document that no test holds is a claim waiting to become false.
 *
 * <p>It asserts the wiring, not any behaviour of FinAssist's own: that the context loads with
 * every context's domain on the classpath, that {@code application.yml} parses, and that the
 * three probes the file configures are actually exposed. The health response touches no FinAssist
 * code.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than {@code TestRestTemplate}, which Spring Boot 4
 * moved out of {@code spring-boot-starter-test} into a separate {@code spring-boot-resttestclient}
 * artefact. Adding a dependency to make an assertion the JDK can already make would be a poor
 * trade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void Given_the_application_When_it_boots_Then_the_health_endpoint_reports_up() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /** {@code management.endpoint.health.probes.enabled} is what puts these two on the wire. */
    @Test
    void Given_the_application_When_it_boots_Then_the_liveness_and_readiness_probes_report_up() throws Exception {
        assertThat(get("/actuator/health/liveness").body()).contains("\"status\":\"UP\"");
        assertThat(get("/actuator/health/readiness").body()).contains("\"status\":\"UP\"");
    }

    /**
     * The exposure list in {@code application.yml} is deliberately short. This is what would fail
     * if someone widened it to {@code "*"} without meaning to.
     */
    @Test
    void Given_the_application_When_an_unexposed_endpoint_is_requested_Then_it_is_not_served() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }
}
