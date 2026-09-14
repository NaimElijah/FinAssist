package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    /**
     * The status line of a {@code GET} naming a {@code Host} of the caller's choosing.
     *
     * <p>Written by hand over a socket because {@link HttpClient} treats {@code Host} as a restricted
     * header and derives it from the URI, which is exactly what a rebound page's browser does not do.
     */
    private String statusLineFor(String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
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

    /**
     * DNS rebinding as the ledger sees it: a request that arrives on its loopback port while naming
     * another host (ADR-027). Actuator is the target because it is served whatever else exists, and
     * the allow-list covers it too. The other cases here send {@code Host: localhost:<port>}, which is
     * why they still pass.
     */
    @Test
    void Given_a_request_naming_a_foreign_host_When_it_reaches_the_ledger_Then_it_is_refused_with_403()
            throws Exception {
        assertThat(statusLineFor("/actuator/health", "evil.example")).startsWith("HTTP/1.1 403");
    }
}
