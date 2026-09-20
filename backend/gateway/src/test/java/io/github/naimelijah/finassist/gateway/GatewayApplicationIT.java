package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.gateway.composition.HostAllowListFilter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;

/**
 * The gateway as a running server: it boots, answers its own health probe, refuses a foreign
 * {@code Host} header, and registers that refusal ahead of every other filter — the same way
 * {@code LedgerApplicationIT} proves the health probe for the ledger.
 *
 * <p>Nothing here exercises a route, OIDC or a session, because none of them exist yet - see
 * {@code GatewayApplication}'s Javadoc. This is the smoke test a later task's routing and
 * security work builds on, not a stand-in for it. The filter-order assertion below checks only
 * that {@code HostAllowListFilter} runs first: once Spring Security registers {@code
 * springSecurityFilterChain} as a filter of its own, that task extends the same assertion with
 * one more comparison against the value already read here, rather than writing a new test.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than {@code TestRestTemplate}, which Spring Boot 4
 * moved out of {@code spring-boot-starter-test} into a separate artefact the ledger also declined
 * to add for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String FOREIGN_HOST = "evil.example";

    /**
     * The JDK's {@link HttpClient} refuses to set the {@code Host} header explicitly, since it is
     * normally derived from the request URI, unless this property names it. Verified against the
     * JDK this project is pinned to (21.0.9): with it set, {@code .header("Host", ...)} overrides
     * the header the server receives while the connection itself still goes to {@code port}, which
     * is exactly what proving the allow-list needs — a request that reaches the gateway under a
     * foreign name. It must be set before {@link HttpClient} is first touched by any test in this
     * module, so it happens in this class's static initialiser, ahead of {@link #CLIENT}.
     */
    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

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
        return get(path, "localhost");
    }

    private HttpResponse<String> get(String path, String host) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Host", host)
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

    @Test
    void Given_a_foreign_host_header_When_requested_Then_it_is_refused_with_a_403_problem() throws Exception {
        HttpResponse<String> response = get("/actuator/health", FOREIGN_HOST);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("content-type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"HOST_NOT_ALLOWED\"").doesNotContain(FOREIGN_HOST);
    }

    /**
     * Every registered filter's order, {@code HostAllowListFilter}'s included, rather than only
     * asserting the filter's own value against the {@link Ordered} constant: comparing against
     * the whole set is what lets a later task extend this test with one more filter — Spring
     * Security's — instead of writing a second one next to it.
     */
    @Test
    void Given_the_gateways_registered_filters_When_compared_Then_the_host_allow_list_filter_runs_first() {
        Map<String, AbstractFilterRegistrationBean> registrations =
                context.getBeansOfType(AbstractFilterRegistrationBean.class);

        int hostAllowListOrder = registrations.values().stream()
                .filter(registration -> registration.getFilter() instanceof HostAllowListFilter)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HostAllowListFilter registration was found"))
                .getOrder();

        assertThat(hostAllowListOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registrations.values())
                .allSatisfy(
                        registration -> assertThat(registration.getOrder()).isGreaterThanOrEqualTo(hostAllowListOrder));
    }
}
