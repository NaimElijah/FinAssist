package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds the observability claims ADR-019 makes, so they cannot quietly become false.
 *
 * <p>Before this existed the README promised structured logs with a correlation id and OTLP
 * traces, and nothing checked either. These tests assert the wiring rather than any FinAssist
 * behaviour. The one that calls a business endpoint, {@code /api/v1/accounts}, does so only to make
 * the ledger write a line; what it asserts is the trace id on that line.
 *
 * <p>That this class boots at all is itself the proof of ADR-019's central promise: no collector
 * runs during a test, and the service starts anyway. Spring Boot 4 disables both metrics and
 * tracing export in tests by default, so {@link AutoConfigureMetrics} and {@link
 * AutoConfigureTracing} are what undo that here. Without the first there is no Prometheus registry to
 * scrape. Without the second, Boot's test support sets {@code management.tracing.export.enabled} to
 * false, which removes W3C propagation along with export, so a caller's {@code traceparent} would not
 * be continued.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The shipped default, pinned so a machine that exports OTEL_EXPORT_ENABLED=true cannot make
        // the correlation test pass without the default configuration ever being exercised.
        properties = "OTEL_EXPORT_ENABLED=false")
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class LedgerObservabilityIT extends PostgresFixture {

    private static final Logger log = LoggerFactory.getLogger(LedgerObservabilityIT.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CALLER_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String CALLER_TRACEPARENT = "00-" + CALLER_TRACE_ID + "-00f067aa0ba902b7-01";

    @LocalServerPort
    private int port;

    @Autowired
    private Tracer tracer;

    /**
     * Stands in for a database the ledger cannot reach. The failure a real outage raises is thrown
     * from the store's own port, so the request travels the real controller, service and problem
     * handler to the one line the ledger writes for it.
     */
    @MockitoBean
    private AccountRepository accounts;

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
     * The Prometheus endpoint is the metrics surface a scraper reads. {@code jvm_memory_used_bytes}
     * is a metric Micrometer registers without any FinAssist code, so this asserts the registry is
     * wired rather than that any particular meter exists.
     */
    @Test
    void Given_the_application_When_the_prometheus_endpoint_is_requested_Then_it_serves_metrics() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    /**
     * Widening the exposure list is the likeliest way to leak configuration, and adding
     * {@code prometheus} to it is exactly the kind of edit that invites a careless {@code "*"}.
     * {@code LedgerApplicationIT} makes the same assertion; it is repeated here because this is
     * the class whose change would break it.
     */
    @Test
    void Given_the_prometheus_endpoint_is_exposed_When_an_unexposed_one_is_requested_Then_it_is_still_not_served()
            throws Exception {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    /**
     * The correlation id reaches a log line through the MDC, which the ECS format renders. Logging
     * inside a span and finding that span's trace id in the output is what proves the two halves
     * are connected; asserting the line parses as JSON proves the structured format is on.
     */
    @Test
    void Given_a_log_written_inside_a_span_When_the_console_is_read_Then_the_line_is_json_carrying_the_trace_id(
            CapturedOutput output) throws Exception {
        ScopedSpan span = tracer.startScopedSpan("observability-test");
        String traceId = span.context().traceId();
        try {
            log.info("Structured logging probe");
        } finally {
            span.end();
        }

        String line = output.getOut()
                .lines()
                .filter(candidate -> candidate.contains("Structured logging probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line was captured for the probe"));

        assertThat(JSON.readTree(line).isObject())
                .as("the console line should be structured JSON, not Spring's default text format")
                .isTrue();
        assertThat(line).contains(traceId);
    }

    /**
     * ADR-019's end-to-end promise, held at the ledger's edge: the browser's {@code traceparent} is
     * continued, not replaced, so the id a caller sent is the id in the ledger's log line. The line is
     * the one {@code LedgerProblemHandler} writes for a 5xx, the ledger's only per-request log, and
     * the class pins the shipped default of export off, because correlation must not depend on a
     * collector being configured.
     */
    @Test
    void Given_a_callers_traceparent_and_export_off_When_the_request_fails_Then_the_log_line_carries_its_trace_id(
            CapturedOutput output) throws Exception {
        when(accounts.all()).thenThrow(new CannotGetJdbcConnectionException("the database is unreachable"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/accounts"))
                .header("traceparent", CALLER_TRACEPARENT)
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode line = output.getOut()
                .lines()
                .filter(candidate -> candidate.contains("Answered 500 with a generic detail"))
                .map(JSON::readTree)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line was captured for the failed request"));
        assertThat(line.path("traceId").asString()).isEqualTo(CALLER_TRACE_ID);
    }

    /**
     * ADR-019's no-op contract, read from the shipped file rather than from the running
     * context.
     *
     * <p>Two reasons it is done this way. {@link AutoConfigureTracing} on this class stops Boot's
     * test support forcing {@code management.tracing.export.enabled} to false, and the class pins
     * {@code OTEL_EXPORT_ENABLED} itself, so the live {@code Environment} would answer for the test
     * rather than for production. And Spring Boot 4.1's {@code
     * OpenTelemetryEnvironmentVariableEnvironmentPostProcessor} maps {@code
     * OTEL_EXPORTER_OTLP_ENDPOINT} onto these properties with {@code addFirst} — so a developer
     * who exports the variable this project now documents would fail an assertion made against
     * the ambient environment. A test whose result depends on the machine it runs on is not a
     * test.
     *
     * <p>Loading {@code application.yml} into an environment with no system property sources
     * asserts what the repository ships: both exporters off unless someone opts in.
     *
     * <p>Trace export is read from the OTLP exporter's own switch. Spring Boot's global {@code
     * management.tracing.export.enabled} also removes W3C propagation, which ADR-019 needs whether or
     * not anything is exported, so the file must never turn that one off.
     */
    @Test
    void Given_the_shipped_configuration_When_read_without_the_environment_Then_export_is_off() throws Exception {
        StandardEnvironment shipped = new StandardEnvironment();
        shipped.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        shipped.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> loaded :
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))) {
            shipped.getPropertySources().addLast(loaded);
        }

        assertThat(shipped.getProperty("management.tracing.export.otlp.enabled"))
                .isEqualTo("false");
        assertThat(shipped.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
        assertThat(shipped.getProperty("management.tracing.export.enabled"))
                .as("the global switch also disables propagation, so turning it off breaks correlation")
                .isNotEqualTo("false");
    }

    /**
     * The endpoint must come from Spring Boot's own environment-variable mapping.
     *
     * <p>Setting it in {@code application.yml} looks harmless and is not: the post-processor
     * installs its property source with {@code addFirst}, so a hand-built value is computed and
     * discarded whenever the standard variable is set, and it ignores the signal-specific
     * {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} entirely.
     */
    @Test
    void Given_the_shipped_configuration_When_read_Then_it_hard_codes_no_otlp_endpoint() throws IOException {
        String yaml;
        try (InputStream source =
                Objects.requireNonNull(getClass().getResourceAsStream("/application.yml"), "application.yml")) {
            yaml = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(yaml)
                .as("Spring Boot maps OTEL_EXPORTER_OTLP_ENDPOINT itself; setting it here overrides"
                        + " the signal-specific variable and is discarded when the standard one is set")
                .doesNotContain("export.otlp")
                .doesNotContain("v1/traces");
    }
}
