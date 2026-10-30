package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.TestTokens;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request-body bound as the running ledger enforces it: a body over {@value
 * RequestBodyLimitFilter#MAX_REQUEST_BODY_BYTES} bytes is refused 413 {@code CONTENT_TOO_LARGE}, whether
 * its length is declared or it arrives chunked, and a body of exactly the limit is served.
 *
 * <p>Each body is a valid account, padded with the whitespace JSON allows after a value, so that only its
 * size decides the outcome. A chunked body is written by hand over a socket, because the JDK's {@link
 * HttpClient} chooses its own framing; the request line, headers and chunks are then exactly the ones
 * named here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBodyLimitIT extends PostgresFixture {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String ACCOUNT = """
            {"name": "Cash", "type": "ASSET", "currency": "ILS"}""";
    private static final int CHUNK_BYTES = 8 * 1024;
    private static final String CRLF = "\r\n";

    /** One client for the class, closed afterwards, for the reason {@code LedgerApplicationIT} gives. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ServletWebServerApplicationContext context;

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @Test
    void Given_a_declared_length_one_byte_over_the_limit_When_posted_Then_it_is_a_413_and_nothing_is_recorded()
            throws Exception {
        HttpResponse<String> response = post(paddedAccount(RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES + 1));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertContentTooLarge(JsonMapper.shared().readTree(response.body()));
        assertThat(accounts()).isEmpty();
    }

    /**
     * The limit runs before Spring Security, so a request carrying no token is refused for its size, not
     * for its missing token: the body is never read by anything that would authenticate it first.
     */
    @Test
    void Given_no_token_and_a_declared_length_over_the_limit_When_posted_Then_it_is_a_413_not_a_401() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(uri(ACCOUNTS))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                paddedAccount(RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES + 1)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertContentTooLarge(JsonMapper.shared().readTree(response.body()));
    }

    @Test
    void Given_a_chunked_body_one_byte_over_the_limit_When_posted_Then_it_is_a_413_and_nothing_is_recorded()
            throws Exception {
        String response = postChunked(paddedAccount(RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES + 1));

        assertThat(response).startsWith("HTTP/1.1 413");
        assertThat(response)
                .containsIgnoringCase(HttpHeaders.CONTENT_TYPE + ": " + MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertContentTooLarge(JsonMapper.shared().readTree(response.substring(response.indexOf(CRLF + CRLF))));
        assertThat(accounts()).isEmpty();
    }

    @Test
    void Given_a_declared_length_of_exactly_the_limit_When_posted_Then_the_account_is_opened() throws Exception {
        HttpResponse<String> response = post(paddedAccount(RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(accounts()).hasSize(1);
    }

    /** The bytes read while counting are the ones the controller then reads: none lost, none repeated. */
    @Test
    void Given_a_chunked_body_of_exactly_the_limit_When_posted_Then_the_account_is_opened() throws Exception {
        String response = postChunked(paddedAccount(RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES));

        assertThat(response).startsWith("HTTP/1.1 201");
        assertThat(accounts())
                .singleElement()
                .satisfies(
                        account -> assertThat(account.path("name").asString()).isEqualTo("Cash"));
    }

    /**
     * Read from the running server, not from the registration's order: the bound must run after the Host
     * allow-list, which refuses a foreign request before anything else sees it (ADR-030), after Spring MVC's
     * request observation, so that a refusal is traced and counted, and before every filter that could read
     * a body, Spring Security's and the form-content filter's included.
     */
    @Test
    void
            Given_the_running_ledger_When_its_filter_chain_is_read_Then_the_body_limit_runs_after_the_host_allow_list_and_before_any_body_reader() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        Context tomcatContext = Arrays.stream(server.getTomcat().getHost().findChildren())
                .map(Context.class::cast)
                .findFirst()
                .orElseThrow();
        List<String> filterNames = Arrays.stream(tomcatContext.findFilterMaps())
                .map(map -> map.getFilterName())
                .toList();
        List<String> filterClasses = filterNames.stream()
                .map(name -> tomcatContext.findFilterDef(name).getFilterClass())
                .toList();

        int hostAllowList = filterClasses.indexOf(HostAllowListFilter.class.getName());
        int observation = filterNames.indexOf("webMvcObservationFilter");
        int bodyLimit = filterClasses.indexOf(RequestBodyLimitFilter.class.getName());
        assertThat(hostAllowList)
                .as("the Host allow-list is registered, in %s", filterNames)
                .isNotNegative();
        assertThat(observation)
                .as("the observation is registered, in %s", filterNames)
                .isNotNegative();
        assertThat(bodyLimit)
                .as("the body limit is registered after the Host allow-list and the observation, in %s", filterNames)
                .isGreaterThan(hostAllowList)
                .isGreaterThan(observation);
        assertThat(filterNames.subList(0, bodyLimit))
                .as("filters ahead of the body limit")
                .doesNotContain("springSecurityFilterChain", "formContentFilter");
    }

    private static void assertContentTooLarge(JsonNode problem) {
        assertThat(problem.path("status").asInt()).isEqualTo(413);
        assertThat(problem.path("type").asString()).isEqualTo("about:blank");
        assertThat(problem.path("code").asString()).isEqualTo("CONTENT_TOO_LARGE");
    }

    /** A valid account, padded with trailing whitespace to exactly {@code bytes} bytes of UTF-8. */
    private static String paddedAccount(int bytes) {
        return ACCOUNT + " ".repeat(bytes - ACCOUNT.getBytes(StandardCharsets.UTF_8).length);
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(uri(ACCOUNTS))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.owner())
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Posts {@code body} as {@value #CHUNK_BYTES}-byte chunks with no declared length; returns the raw response. */
    private String postChunked(String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + ACCOUNTS + " HTTP/1.1" + CRLF
                            + "Host: localhost" + CRLF
                            + HttpHeaders.AUTHORIZATION + ": Bearer " + TestTokens.owner() + CRLF
                            + HttpHeaders.CONTENT_TYPE + ": " + MediaType.APPLICATION_JSON_VALUE + CRLF
                            + HttpHeaders.TRANSFER_ENCODING + ": chunked" + CRLF
                            + HttpHeaders.CONNECTION + ": close" + CRLF + CRLF)
                    .getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
                int length = Math.min(CHUNK_BYTES, bytes.length - offset);
                out.write((Integer.toHexString(length) + CRLF).getBytes(StandardCharsets.US_ASCII));
                out.write(bytes, offset, length);
                out.write(CRLF.getBytes(StandardCharsets.US_ASCII));
            }
            out.write(("0" + CRLF + CRLF).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readToEnd(socket.getInputStream());
        }
    }

    private static String readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        in.transferTo(received);
        return received.toString(StandardCharsets.UTF_8);
    }

    private JsonNode accounts() throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(uri(ACCOUNTS))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.owner())
                        .timeout(TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.shared().readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
