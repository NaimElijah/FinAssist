package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import static io.github.naimelijah.finassist.ledger.ProblemAssertions.requiredProblemMembers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.finassist.ledger.WebSliceSecurity;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ScenarioNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection route as a client sees it: status, content type, body and {@code code} (ADR-028).
 *
 * <p>A {@code @WebMvcTest} slice holds this controller, planning's advice and the composition fallback;
 * the in-port is mocked and answers with projections the real domain computed, so the figures asserted
 * are ones {@code Projection.of} actually produces. {@code PlanningHttpIT} drives the same route through
 * the real services, the store and accounting's balances.
 *
 * <p>The projected scenario opens the first quarter of 2026 with 20000.00 cash and 1000.00 invested, and
 * a car of 60000.00 lands on 15 February, in the step that closes on 1 March. No growth is assumed, so
 * no rounding enters the figures.
 */
@Import(WebSliceSecurity.class)
@WebMvcTest(controllers = ProjectionController.class)
@ExtendWith(OutputCaptureExtension.class)
class ProjectionControllerTest {

    private static final String PROJECTION = "/api/v1/scenarios/{id}/projection";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final Horizon FIRST_QUARTER = new Horizon(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
    private static final ScenarioId SCENARIO = ScenarioId.generate();

    private static final String CURRENT_ACCOUNT = UUID.randomUUID().toString();
    private static final String SAVINGS_ACCOUNT = UUID.randomUUID().toString();
    private static final String PENSION_ACCOUNT = UUID.randomUUID().toString();

    private static final PlannedFlow CAR = PlannedFlow.plan(
            "Car", ils("60000.00"), Direction.OUTFLOW, LocalDate.of(2026, 2, 15), new Recurrence.Once());

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProjectScenarioUseCase projector;

    /** Repeated {@code cash} and {@code invested} parameters reach the port as account references, in order. */
    @Test
    void
            Given_a_scenario_that_overdraws_When_projected_Then_it_is_answered_200_with_every_point_and_the_first_shortfall()
                    throws Exception {
        Projection projected =
                projectionOf(Scenario.of("Buy the car", Currency.ILS, List.of(CAR), GrowthAssumption.none()));
        when(projector.project(new ProjectScenarioCommand(
                        SCENARIO,
                        FIRST_QUARTER,
                        Cadence.MONTHLY,
                        List.of(AccountRef.of(CURRENT_ACCOUNT), AccountRef.of(SAVINGS_ACCOUNT)),
                        List.of(AccountRef.of(PENSION_ACCOUNT)))))
                .thenReturn(projected);

        mvc.perform(get(PROJECTION, SCENARIO)
                        .param("from", "2026-01-01")
                        .param("to", "2026-04-01")
                        .param("step", "MONTHLY")
                        .param("cash", CURRENT_ACCOUNT, SAVINGS_ACCOUNT)
                        .param("invested", PENSION_ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.scenario").value(SCENARIO.toString()))
                .andExpect(jsonPath("$.horizon.from").value("2026-01-01"))
                .andExpect(jsonPath("$.horizon.to").value("2026-04-01"))
                .andExpect(jsonPath("$.step").value("MONTHLY"))
                .andExpect(jsonPath("$.currency").value("ILS"))
                .andExpect(jsonPath("$.points.length()").value(4))
                .andExpect(jsonPath("$.points[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.points[0].cash.amount").value("20000.00"))
                .andExpect(jsonPath("$.points[0].cash.currency").value("ILS"))
                .andExpect(jsonPath("$.points[0].invested.amount").value("1000.00"))
                .andExpect(jsonPath("$.points[0].total.amount").value("21000.00"))
                .andExpect(jsonPath("$.points[1].cash.amount").value("20000.00"))
                .andExpect(jsonPath("$.points[2].date").value("2026-03-01"))
                .andExpect(jsonPath("$.points[2].cash.amount").value("-40000.00"))
                .andExpect(jsonPath("$.points[2].invested.amount").value("1000.00"))
                .andExpect(jsonPath("$.points[2].total.amount").value("-39000.00"))
                .andExpect(jsonPath("$.points[3].date").value("2026-04-01"))
                .andExpect(jsonPath("$.firstShortfall").value("2026-03-01"));
    }

    /** No shortfall is omitted, not written as null, and no account parameters means none named. */
    @Test
    void Given_a_scenario_that_never_overdraws_and_no_accounts_named_When_projected_Then_it_has_no_first_shortfall()
            throws Exception {
        Projection projected = projectionOf(Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none()));
        when(projector.project(
                        new ProjectScenarioCommand(SCENARIO, FIRST_QUARTER, Cadence.MONTHLY, List.of(), List.of())))
                .thenReturn(projected);

        mvc.perform(get(PROJECTION, SCENARIO)
                        .param("from", "2026-01-01")
                        .param("to", "2026-04-01")
                        .param("step", "MONTHLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(4))
                .andExpect(jsonPath("$.points[3].cash.amount").value("20000.00"))
                .andExpect(jsonPath("$.firstShortfall").doesNotHaveJsonPath());
    }

    /** The scenario is named in the path, so its absence is 404, unlike in a comparison's query (ADR-028). */
    @Test
    void Given_an_unknown_scenario_in_the_path_When_projected_Then_it_is_a_404_scenario_not_found_problem()
            throws Exception {
        ScenarioNotFoundException missing = new ScenarioNotFoundException(SCENARIO);
        when(projector.project(any())).thenThrow(missing);

        mvc.perform(firstQuarter())
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("SCENARIO_NOT_FOUND"))
                .andExpect(requiredProblemMembers());
    }

    /**
     * Planning refuses an account named twice as the command is built, so the in-port is never called,
     * and the detail is the message the translation gave while it held the rule (ADR-051).
     */
    @Test
    void Given_an_account_named_as_cash_and_as_invested_When_projected_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        mvc.perform(firstQuarter().param("cash", CURRENT_ACCOUNT).param("invested", CURRENT_ACCOUNT))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail")
                        .value("Account reference " + CURRENT_ACCOUNT
                                + " is named more than once; it would be counted twice"))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(projector);
    }

    /**
     * Nothing is deletable, so a scenario naming a plan the store cannot find means the store is corrupt:
     * the ledger's fault, not the request's (ADR-028). The plan id reaches the log and never the wire.
     */
    @Test
    void Given_a_scenario_naming_a_plan_the_store_cannot_find_When_projected_Then_it_is_a_500_logged_not_served(
            CapturedOutput output) throws Exception {
        PlanId lost = PlanId.generate();
        when(projector.project(any())).thenThrow(new PlanNotFoundException(lost));

        String body = mvc.perform(firstQuarter())
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("The ledger failed to handle this request."))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(lost.toString());
        assertThat(output.getOut().lines().filter(line -> line.contains(lost.toString())))
                .as("the log line carrying the failure")
                .singleElement()
                .satisfies(line -> assertThat(JsonMapper.shared()
                                .readTree(line)
                                .path("log")
                                .path("level")
                                .asString())
                        .isEqualTo("ERROR"));
    }

    /**
     * A horizon that does not tile into steps, and an account reference that is malformed, unknown or in
     * another currency, all reach the controller as the domain's rule signal.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "A horizon of 2026-01-01 to 2026-02-15 is not a whole number of MONTHLY steps",
                "Account reference 3f0c names no account",
                "Account x is held in USD but the scenario is in ILS; FinAssist performs no conversion"
            })
    void Given_a_projection_the_domain_refuses_When_projected_Then_it_is_a_422_rule_violated_problem(String refusal)
            throws Exception {
        when(projector.project(any())).thenThrow(new IllegalArgumentException(refusal));

        mvc.perform(firstQuarter())
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(refusal))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
    }

    /** An empty horizon is refused as well as an inverted one: {@code to} is exclusive. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01", "2025-12-01"})
    void Given_a_horizon_that_does_not_start_before_it_ends_When_projected_Then_it_is_a_422_rule_violated_problem(
            String to) throws Exception {
        mvc.perform(get(PROJECTION, SCENARIO)
                        .param("from", "2026-01-01")
                        .param("to", to)
                        .param("step", "MONTHLY"))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(projector);
    }

    /**
     * The horizon is built, and refused, before the in-port is called: nothing is projected for a span the
     * ledger will not compute (ADR-028).
     */
    @Test
    void Given_a_horizon_one_day_beyond_the_maximum_span_When_projected_Then_it_is_a_422_and_nothing_is_projected()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate oneDayTooFar = from.plusYears(Horizon.MAXIMUM_SPAN_YEARS).plusDays(1);

        mvc.perform(get(PROJECTION, SCENARIO)
                        .param("from", from.toString())
                        .param("to", oneDayTooFar.toString())
                        .param("step", "WEEKLY"))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(projector);
    }

    @Test
    void Given_a_horizon_of_exactly_the_maximum_span_When_projected_Then_it_is_answered_200_with_every_point()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        Horizon widest = new Horizon(from, from.plusYears(Horizon.MAXIMUM_SPAN_YEARS));
        Projection projected = Projection.of(
                Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none()),
                widest,
                Cadence.YEARLY,
                ils("20000.00"),
                ils("1000.00"));
        when(projector.project(new ProjectScenarioCommand(SCENARIO, widest, Cadence.YEARLY, List.of(), List.of())))
                .thenReturn(projected);

        mvc.perform(get(PROJECTION, SCENARIO)
                        .param("from", widest.from().toString())
                        .param("to", widest.to().toString())
                        .param("step", "YEARLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(Horizon.MAXIMUM_SPAN_YEARS + 1))
                .andExpect(jsonPath("$.horizon.to").value(widest.to().toString()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "?to=2026-04-01&step=MONTHLY",
                "?from=2026-01-01&step=MONTHLY",
                "?from=2026-01-01&to=2026-04-01",
                "?from=01/01/2026&to=2026-04-01&step=MONTHLY",
                "?from=2026-01-01&to=2026-04-01&step=FORTNIGHTLY"
            })
    void Given_a_horizon_or_step_that_is_missing_or_malformed_When_projected_Then_it_is_a_400_problem(String query)
            throws Exception {
        mvc.perform(get("/api/v1/scenarios/" + SCENARIO + "/projection" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(projector);
    }

    @Test
    void Given_a_malformed_scenario_id_When_projected_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(get(PROJECTION, "not-a-uuid")
                        .param("from", "2026-01-01")
                        .param("to", "2026-04-01")
                        .param("step", "MONTHLY"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(projector);
    }

    /** A well-formed projection request over the first quarter, monthly, naming no accounts. */
    private static MockHttpServletRequestBuilder firstQuarter() {
        return get(PROJECTION, SCENARIO)
                .param("from", "2026-01-01")
                .param("to", "2026-04-01")
                .param("step", "MONTHLY");
    }

    /** The scenario projected over the first quarter, monthly, from 20000.00 cash and 1000.00 invested. */
    private static Projection projectionOf(Scenario scenario) {
        return Projection.of(scenario, FIRST_QUARTER, Cadence.MONTHLY, ils("20000.00"), ils("1000.00"));
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
