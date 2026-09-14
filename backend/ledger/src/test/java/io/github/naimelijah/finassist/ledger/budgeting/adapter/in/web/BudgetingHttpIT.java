package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

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
 * Budgeting over real HTTP against a real PostgreSQL, with spend crossing the context boundary
 * through the composition root's translation over accounting (ADR-022, ADR-028).
 *
 * <p>The controller tests pin the wire shape against mocked ports. This checks that the parts agree
 * once assembled: the controller finds the hand-built services, {@code AccountingSpendJournal}
 * answers with accounting's own posted entries, and a review folds a categorisation rule over them
 * correctly.
 *
 * <p>Calling accounting's endpoints from this test is fine: {@code
 * LedgerArchitectureTest.contexts_do_not_depend_on_each_other} enforces the slice rule on production
 * code and does not import tests.
 *
 * <p>No {@code @Transactional}, for the reason {@code AccountingHttpIT} gives; {@link PostgresFixture}
 * empties every table between cases instead. The JDK's {@link HttpClient} is used for the reason
 * {@code LedgerApplicationIT} gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BudgetingHttpIT extends PostgresFixture {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String TRANSACTIONS = "/api/v1/transactions";
    private static final String BUDGETS = "/api/v1/budgets";
    private static final String RULES = "/api/v1/categorisation-rules";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** One client for the class, closed afterwards, for the reason {@code LedgerApplicationIT} gives. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    /**
     * A transaction is recorded and left {@code Pending} — nothing clears or reconciles it. It still
     * counts as spend: {@code Transaction.countsTowardsBalance} excludes only a voided transaction, and
     * {@code AccountingSpendJournal} inherits that through {@code ViewJournalUseCase.postedBetween}.
     * "Posted" means recorded and not voided, not confirmed.
     */
    @Test
    void
            Given_a_months_spend_matching_and_missing_its_rule_When_the_budget_is_reviewed_Then_it_reports_spend_and_uncategorised()
                    throws Exception {
        String cash = openAccount("Cash", "ASSET");
        String groceries = openAccount("Groceries", "EXPENSE");
        String budgetId = defineSeptemberBudget();
        addRule("supermarket", "groceries");

        record("2026-09-03", "Supermarket run", groceries, "250.00", cash, "-250.00");
        record("2026-09-05", "Streaming subscription", groceries, "40.00", cash, "-40.00");

        JsonNode review = ok(get(BUDGETS + "/" + budgetId + "/review"));

        assertThat(review.path("budget").asString()).isEqualTo(budgetId);
        JsonNode groceriesCategory = review.path("categories").get(0);
        assertThat(groceriesCategory.path("category").asString()).isEqualTo("groceries");
        assertThat(groceriesCategory.path("limit").path("amount").asString()).isEqualTo("1000.00");
        assertThat(groceriesCategory.path("spent").path("amount").asString()).isEqualTo("250.00");
        assertThat(groceriesCategory.path("remaining").path("amount").asString())
                .isEqualTo("750.00");
        assertThat(groceriesCategory.path("overspent").asBoolean()).isFalse();
        JsonNode transportCategory = review.path("categories").get(1);
        assertThat(transportCategory.path("category").asString()).isEqualTo("transport");
        assertThat(transportCategory.path("spent").path("amount").asString()).isEqualTo("0.00");
        assertThat(review.path("unbudgeted").isArray()).isTrue();
        assertThat(review.path("unbudgeted")).isEmpty();
        assertThat(review.path("uncategorised").path("amount").asString()).isEqualTo("40.00");
    }

    @Test
    void Given_a_defined_budget_When_the_budgets_are_listed_Then_it_is_among_them() throws Exception {
        String budgetId = defineSeptemberBudget();

        JsonNode budgets = ok(get(BUDGETS));

        assertThat(budgets.valueStream().map(budget -> budget.path("id").asString()))
                .contains(budgetId);
    }

    /** A budget for September 2026, in ILS, limiting groceries to 1000.00 and transport to 300.00. */
    private String defineSeptemberBudget() throws IOException, InterruptedException {
        HttpResponse<String> response = post(BUDGETS, """
                {"period": {"start": "2026-09-01", "end": "2026-10-01"}, "currency": "ILS",
                 "limits": [{"category": "groceries", "limit": {"amount": "1000.00", "currency": "ILS"}},
                            {"category": "transport", "limit": {"amount": "300.00", "currency": "ILS"}}]}
                """);
        assertThat(response.statusCode()).as("defining the budget").isEqualTo(201);
        return json(response).path("id").asString();
    }

    private void addRule(String fragment, String category) throws IOException, InterruptedException {
        HttpResponse<String> response = post(RULES, """
                {"fragment": "%s", "category": "%s"}
                """.formatted(fragment, category));
        assertThat(response.statusCode()).as("adding the rule %s", fragment).isEqualTo(201);
    }

    /** Opens an ILS account through the accounting API and returns the id the ledger generated for it. */
    private String openAccount(String name, String type) throws IOException, InterruptedException {
        HttpResponse<String> response = post(ACCOUNTS, """
                {"name": "%s", "type": "%s", "currency": "ILS"}
                """.formatted(name, type));
        assertThat(response.statusCode()).as("opening %s", name).isEqualTo(201);
        return json(response).path("id").asString();
    }

    /** Records a two-entry ILS transaction through the accounting API. It starts, and stays, {@code Pending}. */
    private void record(
            String occurredOn, String description, String debited, String debit, String credited, String credit)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                post(TRANSACTIONS, """
                {"occurredOn": "%s", "description": "%s", "entries": [
                  {"account": "%s", "amount": {"amount": "%s", "currency": "ILS"}},
                  {"account": "%s", "amount": {"amount": "%s", "currency": "ILS"}}]}
                """.formatted(occurredOn, description, debited, debit, credited, credit));
        assertThat(response.statusCode()).as("recording %s", description).isEqualTo(201);
    }

    /** The body of a response that must have been a 200. */
    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("status of %s", response.uri()).isEqualTo(200);
        return json(response);
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
