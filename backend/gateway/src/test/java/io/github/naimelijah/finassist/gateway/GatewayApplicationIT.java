package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.gateway.composition.HostAllowListFilter;
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
import java.util.Arrays;
import java.util.List;
import org.apache.catalina.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/**
 * The gateway as a running server: it boots, answers its own health probe, refuses a foreign
 * {@code Host} header, and runs that refusal ahead of every other filter in the chain the server
 * actually built — the same three claims {@code LedgerApplicationIT} proves for the ledger, using
 * the same techniques.
 *
 * <p>Nothing here exercises a route, OIDC or a session, because none of them exist yet - see
 * {@code GatewayApplication}'s Javadoc. This is the smoke test a later task's routing and
 * security work builds on, not a stand-in for it. The filter-chain assertion below checks only
 * that {@code HostAllowListFilter} is first: once the task that adds Spring Security registers
 * {@code springSecurityFilterChain} as a filter of its own, that task extends the same list with
 * one more position to check, rather than writing a new test.
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

    @Autowired
    private ServletWebServerApplicationContext context;

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
    void Given_the_gateway_When_it_boots_Then_health_reports_up() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /**
     * DNS rebinding as the gateway sees it: a request that arrives on its loopback port while
     * naming another host (ADR-030, carrying ADR-027 forward). Actuator is the target because it
     * is served whatever else exists, and the allow-list covers it too.
     */
    @Test
    void Given_a_request_naming_a_foreign_host_When_it_reaches_the_gateway_Then_it_is_refused_with_403()
            throws Exception {
        assertThat(statusLineFor("/actuator/health", "evil.example")).startsWith("HTTP/1.1 403");
    }

    /**
     * The Host allow-list must see a request before any other filter does (ADR-030), and its
     * registration alone does not guarantee that: Spring's character-encoding filter can share the
     * same {@code Integer.MIN_VALUE} order, and a tie is broken only by registration order, which a
     * Boot upgrade or a new filter could change silently. This reads the chain the running server
     * actually built, exactly as the ledger's equivalent test does.
     */
    @Test
    void Given_the_running_gateway_When_its_filter_chain_is_read_Then_the_host_allow_list_is_first() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        List<String> chain = Arrays.stream(server.getTomcat().getHost().findChildren())
                .map(Context.class::cast)
                .flatMap(web -> Arrays.stream(web.findFilterMaps())
                        .map(map -> web.findFilterDef(map.getFilterName()).getFilterClass()))
                .toList();

        assertThat(chain).first().isEqualTo(HostAllowListFilter.class.getName());
    }
}
