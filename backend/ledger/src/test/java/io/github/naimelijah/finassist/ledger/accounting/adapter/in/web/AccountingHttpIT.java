package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accounting over real HTTP against a real PostgreSQL: the scanned controller, both advices, the
 * services the composition root builds by hand, and the store behind them (ADR-028).
 *
 * <p>{@code AccountControllerTest} pins the wire shape against mocked ports. This checks that the parts
 * agree once assembled: the controller finds the hand-built services, the store's name order reaches
 * the client, and a refusal raised deep in a service still arrives as the advice's problem.
 *
 * <p>No {@code @Transactional}. The requests run on the server's threads, which a test transaction does
 * not reach, so {@link PostgresFixture} empties every table between cases instead. The JDK's {@link
 * HttpClient} is used for the reason {@code LedgerApplicationIT} gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountingHttpIT extends PostgresFixture {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** One client for the class, closed afterwards, for the reason {@code LedgerApplicationIT} gives. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @Test
    void Given_two_accounts_opened_out_of_name_order_When_listed_Then_they_come_back_in_name_order() throws Exception {
        open("Savings", "ASSET", "ILS");
        open("Cash", "ASSET", "ILS");

        HttpResponse<String> response = get(ACCOUNTS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)
                        .valueStream()
                        .map(account -> account.path("name").asString()))
                .containsExactly("Cash", "Savings");
    }

    /** Asserted on the raw body, because only there is {@code "0.00"} distinguishable from {@code 0}. */
    @Test
    void Given_a_newly_opened_account_When_its_balance_is_read_Then_it_is_zero_at_the_currency_scale()
            throws Exception {
        String cash = open("Cash", "ASSET", "ILS");

        HttpResponse<String> response = get(ACCOUNTS + "/" + cash + "/balance");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("account").asString()).isEqualTo(cash);
        assertThat(response.body()).contains("\"balance\":{\"amount\":\"0.00\",\"currency\":\"ILS\"}");
    }

    @Test
    void Given_an_open_account_When_its_name_is_opened_again_Then_it_is_a_409_duplicate_account_name_problem()
            throws Exception {
        open("Cash", "ASSET", "ILS");

        HttpResponse<String> response = post(ACCOUNTS, openRequest("Cash", "ASSET", "USD"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(json(response).path("code").asString()).isEqualTo("DUPLICATE_ACCOUNT_NAME");
    }

    /** Opens an account through the API and returns the id the ledger generated for it. */
    private String open(String name, String type, String currency) throws IOException, InterruptedException {
        HttpResponse<String> response = post(ACCOUNTS, openRequest(name, type, currency));
        assertThat(response.statusCode()).as("opening %s", name).isEqualTo(201);
        return json(response).path("id").asString();
    }

    private static String openRequest(String name, String type, String currency) {
        return """
                {"name": "%s", "type": "%s", "currency": "%s"}
                """.formatted(name, type, currency);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JsonMapper.shared().readTree(response.body());
    }
}
