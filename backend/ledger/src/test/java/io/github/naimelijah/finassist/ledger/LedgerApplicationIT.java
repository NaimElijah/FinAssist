package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.composition.HostAllowListFilter;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.apache.catalina.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ledger as a running server, and what it answers at its edge whatever business endpoints it serves.
 *
 * <p>This exists because the README and the architecture document both claim the ledger "boots and
 * answers Actuator probes", and nothing checked either half of that sentence. A claim in a document
 * that no test holds is a claim waiting to become false. {@code LedgerObservabilityIT} and {@code
 * LedgerContractIT} boot the same server for claims of their own.
 *
 * <p>It asserts the wiring and the edge rather than any business behaviour: that the context loads
 * with every context's domain on the classpath, that {@code application.yml} parses, that the three
 * probes the file configures are exposed, that springdoc serves an OpenAPI document (ADR-028), that a
 * foreign {@code Host} is refused by a filter that runs first (ADR-030), that an unknown route is
 * answered as a problem (ADR-028), and that the data source keeps the database's error detail out of an
 * exception's message.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than {@code TestRestTemplate}, which Spring Boot 4
 * moved out of {@code spring-boot-starter-test} into a separate {@code spring-boot-resttestclient}
 * artefact. Adding a dependency to make an assertion the JDK can already make would be a poor
 * trade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class LedgerApplicationIT extends PostgresFixture {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private ServletWebServerApplicationContext context;

    @Autowired
    private DataSource dataSource;

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
        return get(path, TestTokens.owner());
    }

    private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .GET();
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
     * The exposure list in {@code application.yml} is deliberately short. Widening it to {@code "*"}
     * without meaning to would still not serve this: it matches no {@code permitAll} rule, so the
     * security chain refuses it before Actuator's own routing ever runs (ADR-030) — 401 with no
     * token, 403 with a real one that simply names no route this chain admits. Because that refusal
     * holds whatever the list says, this case cannot catch a widening; {@code LedgerObservabilityIT}
     * pins the list itself, from the endpoints Actuator resolves.
     */
    @Test
    void Given_the_application_When_an_unexposed_endpoint_is_requested_Then_it_is_not_served() throws Exception {
        assertThat(get("/actuator/env", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
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
     * another host (ADR-030). Actuator is the target because it is served whatever else exists, and
     * the allow-list covers it too. The other cases here send {@code Host: localhost:<port>}, which is
     * why they still pass.
     */
    @Test
    void Given_a_request_naming_a_foreign_host_When_it_reaches_the_ledger_Then_it_is_refused_with_403()
            throws Exception {
        assertThat(statusLineFor("/actuator/health", "evil.example")).startsWith("HTTP/1.1 403");
    }

    /**
     * The Host allow-list must see a request before any other filter does (ADR-030), and its
     * registration alone does not guarantee that. Its {@code FilterRegistrationBean} sets {@code
     * Ordered.HIGHEST_PRECEDENCE} ({@code Integer.MIN_VALUE}) explicitly, which already sits strictly
     * before {@code SecurityFilterAutoConfiguration}'s own default order for {@code
     * springSecurityFilterChain} ({@code SecurityProperties.DEFAULT_FILTER_ORDER}, {@code -100}); no
     * tie-break decides that relationship.
     *
     * <p>Spring's own character-encoding filter is a different case, and worth naming here because an
     * earlier version of this comment got it wrong: {@code OrderedCharacterEncodingFilter} is a plain
     * {@code @Bean Filter}, which Boot adapts directly into the servlet context rather than wrapping in
     * an {@code AbstractFilterRegistrationBean} at all, so it was never actually compared against this
     * filter's order by the same mechanism in the first place — restating it as "the same order,
     * tie-broken by registration order" described a mechanism that does not exist. A Boot upgrade or a
     * new filter could still change what the running server builds silently, so this reads the actual
     * chain rather than trusting either explanation.
     */
    @Test
    void Given_the_running_ledger_When_its_filter_chain_is_read_Then_the_host_allow_list_runs_before_spring_security() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        Context tomcatContext = Arrays.stream(server.getTomcat().getHost().findChildren())
                .map(Context.class::cast)
                .findFirst()
                .orElseThrow();
        List<String> filterClasses = Arrays.stream(tomcatContext.findFilterMaps())
                .map(map -> tomcatContext.findFilterDef(map.getFilterName()).getFilterClass())
                .toList();
        List<String> filterNames = Arrays.stream(tomcatContext.findFilterMaps())
                .map(map -> map.getFilterName())
                .toList();

        assertThat(filterClasses).first().isEqualTo(HostAllowListFilter.class.getName());
        // "springSecurityFilterChain" is the fixed bean name both SecurityFilterAutoConfiguration and
        // Spring Security's own DelegatingFilterProxyRegistrationBean use, so matching by name — not by
        // a registration-bean class prefix such as DelegatingFilterProxyRegistrationBean, which any
        // filter registered through that bean type would also satisfy — is what actually identifies
        // this filter rather than merely a filter shaped like it. The Host allow-list's own index is 0,
        // proven by the assertion just above.
        int springSecurityIndex = filterNames.indexOf("springSecurityFilterChain");
        assertThat(springSecurityIndex)
                .as("springSecurityFilterChain must be registered")
                .isGreaterThan(0);
    }

    /**
     * pgjdbc appends the server's {@code Detail} line to an exception's message unless {@code
     * logServerErrorDetail} is off, and for a {@code CHECK} or {@code NOT NULL} violation that line quotes
     * the whole failing row. The ledger logs every 5xx with its exception, so the message is what reaches
     * the log. A unique violation's detail names the duplicated key, which shows whether any detail is
     * kept; the probe table is temporary and rolled back, so the store is untouched.
     */
    @Test
    void Given_the_ledger_data_source_When_a_statement_violates_a_constraint_Then_the_message_carries_no_server_detail()
            throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TEMPORARY TABLE detail_probe (id integer PRIMARY KEY)");
                statement.execute("INSERT INTO detail_probe VALUES (1)");

                assertThatThrownBy(() -> statement.execute("INSERT INTO detail_probe VALUES (1)"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("detail_probe_pkey")
                        .hasMessageNotContaining("Detail")
                        .hasMessageNotContaining("(id)=(1)");
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Nothing in the ledger takes multipart, but Spring MVC parses any request that declares it before a
     * route is chosen, a {@code GET} included. A {@code multipart/form-data} type naming no boundary cannot
     * be parsed, which is the client's mistake: it is answered as any other malformed request (ADR-028),
     * and not logged as a failure of the ledger's.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void Given_a_multipart_request_naming_no_boundary_When_it_reaches_the_ledger_Then_it_is_a_400_and_logs_no_error(
            String method, CapturedOutput output) throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.owner())
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.MULTIPART_FORM_DATA_VALUE)
                        .timeout(TIMEOUT)
                        .method(method, HttpRequest.BodyPublishers.ofString("x"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(JsonMapper.shared().readTree(response.body()).path("code").asString())
                .isEqualTo("MALFORMED_REQUEST");
        assertThat(output.getOut()).doesNotContain("\"level\":\"ERROR\"");
    }

    /**
     * The slices cannot show this: MockMvc never parses a multipart body. A well-formed one, with a
     * boundary and a real part, is parsed by the container and then refused by the write's {@code
     * consumes}, as every body but JSON is (ADR-030).
     */
    @Test
    void Given_a_well_formed_multipart_body_When_posted_to_a_write_Then_it_is_a_415_problem() throws Exception {
        String boundary = "finassist-boundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + "Cash\r\n"
                + "--" + boundary + "--\r\n";

        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/accounts"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.owner())
                        .header(
                                HttpHeaders.CONTENT_TYPE,
                                MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=" + boundary)
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(JsonMapper.shared().readTree(response.body()).path("code").asString())
                .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    /**
     * An unknown route under the API prefix is answered as a problem by the composition fallback,
     * not by Boot's own error JSON (ADR-028). The static-resource handler raises the 404 here, which
     * is why the fallback has to keep Spring MVC's exceptions at their own statuses.
     */
    @Test
    void Given_the_running_ledger_When_an_unknown_api_route_is_requested_Then_it_is_a_404_problem() throws Exception {
        HttpResponse<String> response = get("/api/v1/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(JsonMapper.shared().readTree(response.body()).path("code").asString())
                .isEqualTo("NOT_FOUND");
    }
}
