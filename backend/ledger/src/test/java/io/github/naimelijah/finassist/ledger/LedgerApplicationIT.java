package io.github.naimelijah.finassist.ledger;

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
 * The only test that boots Spring, and the only one that touches HTTP.
 *
 * <p>Everything else in this module is a pure domain test, because everything else in this module
 * is pure domain. This exists because the README and the architecture document both claim the
 * ledger "boots and answers Actuator probes", and until now nothing checked either half of that
 * sentence. A claim in a document that no test holds is a claim waiting to become false.
 *
 * <p>It asserts the wiring, not any behaviour of FinAssist's own: that the context loads with
 * every context's domain on the classpath, that {@code application.yml} parses, that the three
 * probes the file configures are actually exposed, and that springdoc serves an OpenAPI document
 * (ADR-028). The health response touches no FinAssist code.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than {@code TestRestTemplate}, which Spring Boot 4
 * moved out of {@code spring-boot-starter-test} into a separate {@code spring-boot-resttestclient}
 * artefact. Adding a dependency to make an assertion the JDK can already make would be a poor
 * trade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApplicationIT extends PostgresFixture {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    /**
     * One client for the class, closed afterwards. {@link HttpClient} owns a selector thread and
     * an executor and is {@link AutoCloseable} on Java 21, so building one per request left four
     * of them running until the JVM exited.
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

    /**
     * Nothing else in this repository has run springdoc 3.1.1 against Spring Boot 4.1.1
     * (ADR-028); this is the proof that the pairing starts a context and serves a document rather
     * than a claim taken on faith from the release notes.
     */
    @Test
    void Given_the_running_ledger_When_its_api_document_is_requested_Then_an_openapi_document_is_served()
            throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsPattern("\"openapi\"\\s*:\\s*\"3\\.");
    }
}
