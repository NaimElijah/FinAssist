package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

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
 * Planning over real HTTP against a real PostgreSQL: the scanned controllers, planning's advice, the
 * services the composition root builds by hand, and the store behind them (ADR-028).
 *
 * <p>The controller tests pin the wire shape against mocked ports. This checks that the parts agree once
 * assembled: the controllers find the hand-built services, the store's order reaches the client, a
 * lifecycle change survives the round trip, and a refusal raised deep in the domain still arrives as the
 * right problem.
 *
 * <p>No {@code @Transactional}, for the reason {@code AccountingHttpIT} gives; {@link PostgresFixture}
 * empties every table between cases instead. The JDK's {@link HttpClient} is used for the reason {@code
 * LedgerApplicationIT} gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanningHttpIT extends PostgresFixture {

    private static final String PLANNED_FLOWS = "/api/v1/planned-flows";
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
        return CLIENT.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JsonMapper.shared().readTree(response.body());
    }
}
