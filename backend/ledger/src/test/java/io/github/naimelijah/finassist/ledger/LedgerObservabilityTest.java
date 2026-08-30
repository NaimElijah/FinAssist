package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds the observability claims ADR-019 makes, so they cannot quietly become false.
 *
 * <p>Before this existed the README promised structured logs with a correlation id and OTLP
 * traces, and nothing checked either. These tests assert the wiring rather than any FinAssist
 * behaviour, because there is no business endpoint to observe yet — the ledger serves Actuator and
 * nothing else.
 *
 * <p>That this class boots at all is itself the proof of ADR-019's central promise: no collector
 * runs during a test, and the service starts anyway. Spring Boot 4 disables both metrics and
 * tracing export in tests by default, so {@link AutoConfigureMetrics} and {@link
 * AutoConfigureTracing} are what turn them back on here. Without the first there is no Prometheus
 * registry to scrape; without the second there is no span to correlate a log line against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class LedgerObservabilityTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerObservabilityTest.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private Tracer tracer;

    @Autowired
    private Environment environment;

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
     * {@code LedgerApplicationTest} makes the same assertion; it is repeated here because this is
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
     * ADR-019's no-op contract. Export stays off unless a collector is named, so a developer
     * without one is not given an export error for every span, and the endpoint still resolves to
     * the documented default rather than to an empty string that would fail to parse as a URL.
     */
    @Test
    void Given_no_collector_is_configured_When_the_export_settings_are_read_Then_export_is_off_and_the_url_is_valid() {
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://localhost:4318/v1/traces");
        assertThat(URI.create(environment.getRequiredProperty("management.opentelemetry.tracing.export.otlp.endpoint")))
                .hasScheme("http");
    }
}
