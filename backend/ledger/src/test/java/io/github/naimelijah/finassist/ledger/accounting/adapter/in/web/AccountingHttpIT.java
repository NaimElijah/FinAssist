package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accounting over real HTTP against a real PostgreSQL: the scanned controllers, both advices, the
 * services the composition root builds by hand, and the store behind them (ADR-028).
 *
 * <p>The controller tests pin the wire shape against mocked ports. This checks that the parts agree once
 * assembled: the controllers find the hand-built services, the store's order reaches the client, a
 * lifecycle change survives the append-only journal, and a refusal raised deep in a service or the
 * domain still arrives as the right problem.
 *
 * <p>No {@code @Transactional}. The requests run on the server's threads, which a test transaction does
 * not reach, so {@link PostgresFixture} empties every table between cases instead. The JDK's {@link
 * HttpClient} is used for the reason {@code LedgerApplicationIT} gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountingHttpIT extends PostgresFixture {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String TRANSACTIONS = "/api/v1/transactions";
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
        open("Savings", "ASSET");
        open("Cash", "ASSET");

        JsonNode accounts = ok(get(ACCOUNTS));

        assertThat(accounts.valueStream().map(account -> account.path("name").asString()))
                .containsExactly("Cash", "Savings");
    }

    /** Asserted on the raw body, because only there is {@code "0.00"} distinguishable from {@code 0}. */
    @Test
    void Given_a_newly_opened_account_When_its_balance_is_read_Then_it_is_zero_at_the_currency_scale()
            throws Exception {
        String cash = open("Cash", "ASSET");

        HttpResponse<String> response = get(ACCOUNTS + "/" + cash + "/balance");

        assertThat(ok(response).path("account").asString()).isEqualTo(cash);
        assertThat(response.body()).contains("\"balance\":{\"amount\":\"0.00\",\"currency\":\"ILS\"}");
    }

    @Test
    void Given_an_open_account_When_its_name_is_opened_again_Then_it_is_a_409_duplicate_account_name_problem()
            throws Exception {
        open("Cash", "ASSET");

        HttpResponse<String> response = post(ACCOUNTS, """
                {"name": "Cash", "type": "ASSET", "currency": "USD"}
                """);

        assertProblem(response, 409, "DUPLICATE_ACCOUNT_NAME");
    }

    @Test
    void Given_a_balanced_transaction_When_recorded_and_then_cleared_Then_it_moves_from_pending_to_cleared()
            throws Exception {
        String cash = open("Cash", "ASSET");
        String groceries = open("Groceries", "EXPENSE");

        HttpResponse<String> recorded =
                post(TRANSACTIONS, recordRequest("2026-03-03", "Groceries", groceries, "12.50", cash, "-12.50"));

        assertThat(recorded.statusCode()).isEqualTo(201);
        assertThat(recorded.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
        JsonNode pending = json(recorded).path("state");
        assertThat(pending.path("status").asString()).isEqualTo("PENDING");
        assertThat(pending.path("on").isMissingNode()).isTrue();

        String id = json(recorded).path("id").asString();
        JsonNode cleared = ok(post(TRANSACTIONS + "/" + id + "/clear", transitionRequest("2026-03-05")));

        assertThat(cleared.path("id").asString()).isEqualTo(id);
        assertThat(cleared.path("state").path("status").asString()).isEqualTo("CLEARED");
        assertThat(cleared.path("state").path("on").asString()).isEqualTo("2026-03-05");
    }

    /**
     * A voided transaction stays in both lists with its reason, and leaves every balance: voiding is
     * history, not deletion (ADR-020).
     */
    @Test
    void Given_a_cleared_and_a_voided_transaction_When_the_journal_is_read_Then_both_are_listed_but_one_counts()
            throws Exception {
        String cash = open("Cash", "ASSET");
        String groceries = open("Groceries", "EXPENSE");
        String kept = record("2026-03-03", "Groceries", groceries, "12.50", cash, "-12.50");
        ok(post(TRANSACTIONS + "/" + kept + "/clear", transitionRequest("2026-03-05")));
        String duplicate = record("2026-03-04", "Groceries again", groceries, "30.00", cash, "-30.00");
        ok(post(TRANSACTIONS + "/" + duplicate + "/void", voidRequest("2026-03-06", "entered twice")));

        JsonNode period = ok(get(TRANSACTIONS + "?from=2026-03-01&to=2026-04-01"));
        JsonNode forCash = ok(get(ACCOUNTS + "/" + cash + "/transactions"));

        assertThat(period.valueStream()
                        .map(transaction -> transaction.path("id").asString()))
                .containsExactly(kept, duplicate);
        assertThat(period.get(0).path("state").path("status").asString()).isEqualTo("CLEARED");
        assertThat(period.get(1).path("state").path("status").asString()).isEqualTo("VOIDED");
        assertThat(period.get(1).path("state").path("reason").asString()).isEqualTo("entered twice");
        assertThat(forCash.valueStream()
                        .map(transaction -> transaction.path("id").asString()))
                .containsExactly(kept, duplicate);
        assertThat(get(ACCOUNTS + "/" + groceries + "/balance").body()).contains("\"amount\":\"12.50\"");
        assertThat(get(ACCOUNTS + "/" + cash + "/balance").body()).contains("\"amount\":\"-12.50\"");
    }

    @Test
    void Given_entries_that_do_not_sum_to_zero_When_recorded_Then_it_is_a_422_rule_violated_problem() throws Exception {
        String cash = open("Cash", "ASSET");
        String groceries = open("Groceries", "EXPENSE");

        HttpResponse<String> response =
                post(TRANSACTIONS, recordRequest("2026-03-03", "Groceries", groceries, "12.50", cash, "-11.50"));

        assertProblem(response, 422, "RULE_VIOLATED");
    }

    /** An account named in a body is not the resource the request is about, so it is 422, not 404 (ADR-028). */
    @Test
    void Given_an_entry_naming_an_account_that_does_not_exist_When_recorded_Then_it_is_a_422_unknown_account_problem()
            throws Exception {
        String cash = open("Cash", "ASSET");
        String nowhere = UUID.randomUUID().toString();

        HttpResponse<String> response =
                post(TRANSACTIONS, recordRequest("2026-03-03", "Groceries", nowhere, "12.50", cash, "-12.50"));

        assertProblem(response, 422, "UNKNOWN_ACCOUNT");
    }

    @Test
    void Given_a_voided_transaction_When_reconciled_Then_it_is_a_409_illegal_transition_problem() throws Exception {
        String cash = open("Cash", "ASSET");
        String groceries = open("Groceries", "EXPENSE");
        String voided = record("2026-03-03", "Groceries", groceries, "12.50", cash, "-12.50");
        ok(post(TRANSACTIONS + "/" + voided + "/void", voidRequest("2026-03-04", "entered twice")));

        HttpResponse<String> response =
                post(TRANSACTIONS + "/" + voided + "/reconcile", transitionRequest("2026-03-05"));

        assertProblem(response, 409, "ILLEGAL_TRANSITION");
    }

    /** Opens an ILS account through the API and returns the id the ledger generated for it. */
    private String open(String name, String type) throws IOException, InterruptedException {
        HttpResponse<String> response = post(ACCOUNTS, """
                {"name": "%s", "type": "%s", "currency": "ILS"}
                """.formatted(name, type));
        assertThat(response.statusCode()).as("opening %s", name).isEqualTo(201);
        return json(response).path("id").asString();
    }

    /** Records a two-entry ILS transaction through the API and returns the id the ledger generated for it. */
    private String record(
            String occurredOn, String description, String debited, String debit, String credited, String credit)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                post(TRANSACTIONS, recordRequest(occurredOn, description, debited, debit, credited, credit));
        assertThat(response.statusCode()).as("recording %s", description).isEqualTo(201);
        return json(response).path("id").asString();
    }

    private static String recordRequest(
            String occurredOn, String description, String debited, String debit, String credited, String credit) {
        return """
                {"occurredOn": "%s", "description": "%s", "entries": [
                  {"account": "%s", "amount": {"amount": "%s", "currency": "ILS"}},
                  {"account": "%s", "amount": {"amount": "%s", "currency": "ILS"}}]}
                """.formatted(occurredOn, description, debited, debit, credited, credit);
    }

    private static String transitionRequest(String on) {
        return """
                {"on": "%s"}
                """.formatted(on);
    }

    private static String voidRequest(String on, String reason) {
        return """
                {"on": "%s", "reason": "%s"}
                """.formatted(on, reason);
    }

    /** The body of a response that must have been a 200. */
    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("status of %s", response.uri()).isEqualTo(200);
        return json(response);
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(json(response).path("code").asString()).isEqualTo(code);
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
