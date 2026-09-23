package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Planning over real HTTP against a real PostgreSQL: the scanned controllers, planning's advice, the
 * services the composition root builds by hand, and the store behind them (ADR-028).
 *
 * <p>The controller tests pin the wire shape against mocked ports. This checks that the parts agree once
 * assembled: the controllers find the hand-built services, the store's order reaches the client, a
 * lifecycle change survives the round trip, and a refusal raised deep in the domain still arrives as the
 * right problem.
 *
 * <p>A projection opens from balances accounting holds, so the forecasting cases open an account and record
 * its opening balance through accounting's own endpoints, and the balance reaches planning only through
 * the composition root's translation (ADR-022). Calling accounting from this test is fine: {@code
 * LedgerArchitectureTest.contexts_do_not_depend_on_each_other} reads production code and does not import
 * tests.
 *
 * <p>No {@code @Transactional}, for the reason {@code AccountingHttpIT} gives; {@link PostgresFixture}
 * empties every table between cases instead. The JDK's {@link HttpClient} is used for the reason {@code
 * LedgerApplicationIT} gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanningHttpIT extends PostgresFixture {

    private static final String PLANNED_FLOWS = "/api/v1/planned-flows";
    private static final String SCENARIOS = "/api/v1/scenarios";
    private static final String COMPARISONS = "/api/v1/scenario-comparisons";
    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String TRANSACTIONS = "/api/v1/transactions";

    /** Every month of 2026, stepped monthly: thirteen points, from the opening one to the closing one. */
    private static final String YEAR_2026 = "from=2026-01-01&to=2027-01-01&step=MONTHLY";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** A salary of 10000.00 ILS on the first of every month in 2026. */
    private static final String MONTHLY_SALARY = """
            {"description": "Salary", "amount": {"amount": "10000.00", "currency": "ILS"}, "direction": "INFLOW",
             "startsOn": "2026-01-01",
             "recurrence": {"kind": "UNTIL", "cadence": "MONTHLY", "interval": 1, "until": "2027-01-01"}}
            """;

    /** A car bought outright for 60000.00 ILS on 15 February 2026. */
    private static final String CAR = """
            {"description": "Car", "amount": {"amount": "60000.00", "currency": "ILS"}, "direction": "OUTFLOW",
             "startsOn": "2026-02-15", "recurrence": {"kind": "ONCE"}}
            """;

    /** One client for the class, closed afterwards, for the reason {@code LedgerApplicationIT} gives. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    /**
     * Planned out of start-date order, listed in it. The car's amount stays positive on the wire while its
     * direction says it leaves (ADR-015), and a realised and a cancelled flow are both still listed: they
     * are history, not deletions.
     */
    @Test
    void
            Given_two_planned_flows_When_one_is_committed_and_realised_and_the_other_cancelled_Then_both_are_listed_in_their_states()
                    throws Exception {
        HttpResponse<String> plannedCar = post(PLANNED_FLOWS, CAR);
        String salary = plan(MONTHLY_SALARY);

        assertThat(plannedCar.statusCode()).isEqualTo(201);
        assertThat(plannedCar.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
        String car = json(plannedCar).path("id").asString();

        JsonNode planned = ok(get(PLANNED_FLOWS));

        assertThat(planned.valueStream().map(flow -> flow.path("id").asString()))
                .containsExactly(salary, car);
        JsonNode listedSalary = planned.get(0);
        assertThat(listedSalary.path("direction").asString()).isEqualTo("INFLOW");
        assertThat(listedSalary.path("recurrence").path("kind").asString()).isEqualTo("UNTIL");
        assertThat(listedSalary.path("recurrence").path("interval").asInt()).isEqualTo(1);
        assertThat(listedSalary.path("recurrence").path("until").asString()).isEqualTo("2027-01-01");
        assertThat(listedSalary.path("recurrence").path("count").isMissingNode())
                .isTrue();
        JsonNode listedCar = planned.get(1);
        assertThat(listedCar.path("amount").path("amount").asString()).isEqualTo("60000.00");
        assertThat(listedCar.path("direction").asString()).isEqualTo("OUTFLOW");
        assertThat(listedCar.path("recurrence").path("kind").asString()).isEqualTo("ONCE");
        assertThat(listedCar.path("state").path("status").asString()).isEqualTo("PLANNED");
        assertThat(listedCar.path("state").path("on").isMissingNode()).isTrue();

        JsonNode committed = ok(post(PLANNED_FLOWS + "/" + car + "/commit", transitionRequest("2026-01-10")));
        assertThat(committed.path("state").path("status").asString()).isEqualTo("COMMITTED");
        assertThat(committed.path("state").path("on").asString()).isEqualTo("2026-01-10");

        JsonNode realised = ok(post(PLANNED_FLOWS + "/" + car + "/realise", transitionRequest("2026-02-15")));
        assertThat(realised.path("state").path("status").asString()).isEqualTo("REALISED");
        assertThat(realised.path("state").path("on").asString()).isEqualTo("2026-02-15");

        JsonNode cancelled =
                ok(post(PLANNED_FLOWS + "/" + salary + "/cancel", cancelRequest("2026-01-20", "changed jobs")));
        assertThat(cancelled.path("state").path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("state").path("reason").asString()).isEqualTo("changed jobs");

        JsonNode afterwards = ok(get(PLANNED_FLOWS));

        assertThat(afterwards
                        .valueStream()
                        .map(flow -> flow.path("state").path("status").asString()))
                .containsExactly("CANCELLED", "REALISED");
    }

    @Test
    void Given_a_flow_still_planned_When_realised_Then_it_is_a_409_illegal_transition_problem() throws Exception {
        String car = plan(CAR);

        HttpResponse<String> response = post(PLANNED_FLOWS + "/" + car + "/realise", transitionRequest("2026-02-15"));

        assertProblem(response, 409, "ILLEGAL_TRANSITION");
    }

    @Test
    void Given_a_plan_that_does_not_exist_When_committed_Then_it_is_a_404_plan_not_found_problem() throws Exception {
        HttpResponse<String> response =
                post(PLANNED_FLOWS + "/" + UUID.randomUUID() + "/commit", transitionRequest("2026-01-10"));

        assertProblem(response, 404, "PLAN_NOT_FOUND");
    }

    /** The sign reaches {@code PlannedFlow} as sent, and the domain refuses it; nothing flips it on the way. */
    @Test
    void Given_a_negative_amount_When_planned_Then_it_is_a_422_rule_violated_problem() throws Exception {
        HttpResponse<String> response = post(PLANNED_FLOWS, CAR.replace("\"60000.00\"", "\"-60000.00\""));

        assertProblem(response, 422, "RULE_VIOLATED");
    }

    /**
     * The forecasting path end to end, over HTTP only.
     *
     * <p>The cash account opens at 20000.00. A salary of 10000.00 lands on the first of every month, and a
     * committed car of 60000.00 on 15 February. The point closing January holds 30000.00; the step closing
     * on 1 March takes in the February salary and pays for the car, leaving -20000.00, which is the first
     * shortfall. Ten more salaries recover it, and the year closes at 80000.00, against the baseline's
     * untouched 20000.00.
     *
     * <p>Cancelling the car is then visible in the very next projection, with nothing redefined, because
     * {@code ForecastService} re-reads a scenario's flows before every projection: the year closes at
     * 140000.00 and the cash is never negative.
     */
    @Test
    void
            Given_a_committed_purchase_that_overdraws_cash_When_projected_compared_and_then_cancelled_Then_the_shortfall_appears_diverges_and_goes()
                    throws Exception {
        String cash = openAccount("Cash", "ASSET");
        String equity = openAccount("Opening balance", "EQUITY");
        recordOpeningBalance(cash, equity, "20000.00");
        String salary = plan(MONTHLY_SALARY);
        String car = plan(CAR);
        ok(post(PLANNED_FLOWS + "/" + car + "/commit", transitionRequest("2026-01-10")));
        String buyTheCar = define("Buy the car", "0", salary, car);
        String doNothing = define("Do nothing", "0");

        JsonNode projection = ok(get(SCENARIOS + "/" + buyTheCar + "/projection?" + YEAR_2026 + "&cash=" + cash));

        assertThat(projection.path("scenario").asString()).isEqualTo(buyTheCar);
        assertThat(projection.path("currency").asString()).isEqualTo("ILS");
        JsonNode points = projection.path("points");
        assertThat(points).hasSize(13);
        assertThat(cashOn(points, 0)).isEqualTo("20000.00");
        assertThat(cashOn(points, 1)).isEqualTo("30000.00");
        assertThat(points.get(2).path("date").asString()).isEqualTo("2026-03-01");
        assertThat(cashOn(points, 2)).isEqualTo("-20000.00");
        assertThat(points.get(12).path("date").asString()).isEqualTo("2027-01-01");
        assertThat(cashOn(points, 12)).isEqualTo("80000.00");
        assertThat(points.get(12).path("invested").path("amount").asString()).isEqualTo("0.00");
        assertThat(points.get(12).path("total").path("amount").asString()).isEqualTo("80000.00");
        assertThat(projection.path("firstShortfall").asString()).isEqualTo("2026-03-01");

        JsonNode comparison = ok(get(COMPARISONS + "?scenario=" + buyTheCar + "&scenario=" + doNothing + "&baseline="
                + doNothing + "&" + YEAR_2026 + "&cash=" + cash));

        assertThat(comparison.path("baseline").asString()).isEqualTo(doNothing);
        JsonNode compared = comparison.path("scenarios");
        assertThat(compared.valueStream().map(scenario -> scenario.path("id").asString()))
                .containsExactly(buyTheCar, doNothing);
        assertThat(compared.get(0).path("name").asString()).isEqualTo("Buy the car");
        JsonNode divergence = compared.get(0).path("divergence");
        assertThat(divergence).hasSize(13);
        assertThat(divergence.get(2).path("cash").path("amount").asString()).isEqualTo("-40000.00");
        assertThat(divergence.get(12).path("total").path("amount").asString()).isEqualTo("60000.00");
        assertThat(compared.get(1).path("divergence")).isEmpty();
        assertThat(compared.get(1).path("endState").path("cash").path("amount").asString())
                .isEqualTo("20000.00");

        ok(post(PLANNED_FLOWS + "/" + car + "/cancel", cancelRequest("2026-01-20", "bought second-hand instead")));
        JsonNode reprojected = ok(get(SCENARIOS + "/" + buyTheCar + "/projection?" + YEAR_2026 + "&cash=" + cash));

        assertThat(reprojected.path("firstShortfall").isMissingNode()).isTrue();
        assertThat(cashOn(reprojected.path("points"), 12)).isEqualTo("140000.00");
    }

    /** Listed by name whatever order they were defined in, each with its flows and a plain growth rate. */
    @Test
    void Given_two_scenarios_defined_out_of_name_order_When_listed_Then_they_come_back_in_name_order()
            throws Exception {
        String car = plan(CAR);
        String doNothing = define("Do nothing", "0");
        String buyTheCar = define("Buy the car", "0.005", car);

        JsonNode scenarios = ok(get(SCENARIOS));

        assertThat(scenarios.valueStream().map(scenario -> scenario.path("id").asString()))
                .containsExactly(buyTheCar, doNothing);
        assertThat(scenarios.get(0).path("flows").get(0).asString()).isEqualTo(car);
        assertThat(scenarios.get(0).path("growthRatePerStep").asString()).isEqualTo("0.005");
        assertThat(scenarios.get(1).path("flows")).isEmpty();
    }

    /** A flow named in the body is part of what the request asks for, so an unknown one is 422 (ADR-028). */
    @Test
    void Given_a_scenario_naming_a_plan_that_does_not_exist_When_defined_Then_it_is_a_422_unknown_plan_problem()
            throws Exception {
        HttpResponse<String> response =
                post(SCENARIOS, defineRequest("Phantom", "0", UUID.randomUUID().toString()));

        assertProblem(response, 422, "UNKNOWN_PLAN");
    }

    /** One unknown scenario, two statuses: 404 named in a projection's path, 422 named in a comparison's query. */
    @Test
    void Given_a_scenario_that_does_not_exist_When_projected_by_path_and_compared_by_query_Then_it_is_404_then_422()
            throws Exception {
        String doNothing = define("Do nothing", "0");
        String nowhere = UUID.randomUUID().toString();

        assertProblem(get(SCENARIOS + "/" + nowhere + "/projection?" + YEAR_2026), 404, "SCENARIO_NOT_FOUND");
        assertProblem(
                get(COMPARISONS + "?scenario=" + doNothing + "&scenario=" + nowhere + "&baseline=" + doNothing + "&"
                        + YEAR_2026),
                422,
                "UNKNOWN_SCENARIO");
    }

    /** Six weeks does not tile into months; the last part-step would hold flows no point reports (ADR-016). */
    @Test
    void Given_a_horizon_that_is_not_a_whole_number_of_steps_When_projected_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        String doNothing = define("Do nothing", "0");

        HttpResponse<String> response =
                get(SCENARIOS + "/" + doNothing + "/projection?from=2026-01-01&to=2026-02-15&step=MONTHLY");

        assertProblem(response, 422, "RULE_VIOLATED");
    }

    /** The composition root's translation turns accounting's not-found into planning's rule signal. */
    @Test
    void Given_a_cash_account_reference_that_names_no_account_When_projected_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        String doNothing = define("Do nothing", "0");

        HttpResponse<String> response =
                get(SCENARIOS + "/" + doNothing + "/projection?" + YEAR_2026 + "&cash=" + UUID.randomUUID());

        assertProblem(response, 422, "RULE_VIOLATED");
    }

    /** Opens an ILS account through the accounting API and returns the id the ledger generated for it. */
    private String openAccount(String name, String type) throws IOException, InterruptedException {
        HttpResponse<String> response = post(ACCOUNTS, """
                {"name": "%s", "type": "%s", "currency": "ILS"}
                """.formatted(name, type));
        assertThat(response.statusCode()).as("opening %s", name).isEqualTo(201);
        return json(response).path("id").asString();
    }

    /** Debits the cash account and credits the equity account by the amount, on the last day of 2025. */
    private void recordOpeningBalance(String cash, String equity, String amount)
            throws IOException, InterruptedException {
        HttpResponse<String> response = post(TRANSACTIONS, """
                {"occurredOn": "2025-12-31", "description": "Opening balance", "entries": [
                  {"account": "%s", "amount": {"amount": "%s", "currency": "ILS"}},
                  {"account": "%s", "amount": {"amount": "-%s", "currency": "ILS"}}]}
                """.formatted(cash, amount, equity, amount));
        assertThat(response.statusCode()).as("recording the opening balance").isEqualTo(201);
    }

    /** Defines an ILS scenario over the given flows and returns the id the ledger generated for it. */
    private String define(String name, String growthRatePerStep, String... flows)
            throws IOException, InterruptedException {
        HttpResponse<String> response = post(SCENARIOS, defineRequest(name, growthRatePerStep, flows));
        assertThat(response.statusCode()).as("defining %s", name).isEqualTo(201);
        return json(response).path("id").asString();
    }

    private static String defineRequest(String name, String growthRatePerStep, String... flows) {
        String ids = Arrays.stream(flows).map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
        return """
                {"name": "%s", "currency": "ILS", "flows": [%s], "growthRatePerStep": "%s"}
                """.formatted(name, ids, growthRatePerStep);
    }

    private static String cashOn(JsonNode points, int index) {
        return points.get(index).path("cash").path("amount").asString();
    }

    /** Plans a flow through the API and returns the id the ledger generated for it. */
    private String plan(String request) throws IOException, InterruptedException {
        HttpResponse<String> response = post(PLANNED_FLOWS, request);
        assertThat(response.statusCode()).as("planning %s", request).isEqualTo(201);
        return json(response).path("id").asString();
    }

    private static String transitionRequest(String on) {
        return """
                {"on": "%s"}
                """.formatted(on);
    }

    private static String cancelRequest(String on, String reason) {
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
        return CLIENT.send(
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.owner())
                        .timeout(TIMEOUT)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JsonMapper.shared().readTree(response.body());
    }
}
