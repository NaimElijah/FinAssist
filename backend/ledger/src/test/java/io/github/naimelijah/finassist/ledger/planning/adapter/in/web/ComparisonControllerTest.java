package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.finassist.ledger.WebSliceSecurity;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.CompareScenariosCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.CompareScenariosUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ListScenariosUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ScenarioNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The comparison route as a client sees it: status, content type, body and {@code code} (ADR-028).
 *
 * <p>A {@code @WebMvcTest} slice holds this controller, planning's advice and the composition fallback;
 * both in-ports are mocked and answer with a comparison the real domain computed. {@code PlanningHttpIT}
 * drives the same route through the real services and the store.
 *
 * <p>Two scenarios open the first quarter of 2026 with 20000.00 cash: "Do nothing", and "Buy the car",
 * where 60000.00 leaves on 15 February, in the step that closes on 1 March.
 */
@Import(WebSliceSecurity.class)
@WebMvcTest(controllers = ComparisonController.class)
@ExtendWith(OutputCaptureExtension.class)
class ComparisonControllerTest {

    private static final String COMPARISONS = "/api/v1/scenario-comparisons";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final Horizon FIRST_QUARTER = new Horizon(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
    private static final String CURRENT_ACCOUNT = UUID.randomUUID().toString();

    private static final PlannedFlow CAR = PlannedFlow.plan(
            "Car",
            Money.of("60000.00", Currency.ILS),
            Direction.OUTFLOW,
            LocalDate.of(2026, 2, 15),
            new Recurrence.Once());

    private static final Scenario DO_NOTHING = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());
    private static final Scenario BUY_THE_CAR =
            Scenario.of("Buy the car", Currency.ILS, List.of(CAR), GrowthAssumption.none());

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CompareScenariosUseCase comparer;

    @MockitoBean
    private ListScenariosUseCase lister;

    /**
     * Each scenario carries its own points and end state, and its divergence from the baseline. The
     * baseline's own divergence is an empty list: the domain leaves the baseline out, because its distance
     * from itself is zero at every step.
     *
     * <p>The request names the baseline second, and the comparison answers in the reverse of the request's
     * order. A response that followed the comparison's own order, or took the first scenario named as the
     * baseline, fails here.
     */
    @Test
    void Given_the_baseline_named_second_When_compared_Then_scenarios_follow_the_request_not_the_comparison()
            throws Exception {
        when(comparer.compare(new CompareScenariosCommand(
                        List.of(BUY_THE_CAR.id(), DO_NOTHING.id()),
                        FIRST_QUARTER,
                        Cadence.MONTHLY,
                        List.of(AccountRef.of(CURRENT_ACCOUNT)),
                        List.of())))
                .thenReturn(comparisonOf(DO_NOTHING, BUY_THE_CAR));
        when(lister.all()).thenReturn(List.of(BUY_THE_CAR, DO_NOTHING));

        mvc.perform(firstQuarter(DO_NOTHING.id(), BUY_THE_CAR.id(), DO_NOTHING.id())
                        .param("cash", CURRENT_ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.horizon.from").value("2026-01-01"))
                .andExpect(jsonPath("$.horizon.to").value("2026-04-01"))
                .andExpect(jsonPath("$.step").value("MONTHLY"))
                .andExpect(jsonPath("$.currency").value("ILS"))
                .andExpect(jsonPath("$.baseline").value(DO_NOTHING.id().toString()))
                .andExpect(jsonPath("$.scenarios.length()").value(2))
                .andExpect(jsonPath("$.scenarios[0].id").value(BUY_THE_CAR.id().toString()))
                .andExpect(jsonPath("$.scenarios[0].name").value("Buy the car"))
                .andExpect(jsonPath("$.scenarios[0].points.length()").value(4))
                .andExpect(jsonPath("$.scenarios[0].points[2].cash.amount").value("-40000.00"))
                .andExpect(jsonPath("$.scenarios[0].endState.cash.amount").value("-40000.00"))
                .andExpect(jsonPath("$.scenarios[0].endState.invested.amount").value("0.00"))
                .andExpect(jsonPath("$.scenarios[0].divergence.length()").value(4))
                .andExpect(jsonPath("$.scenarios[0].divergence[1].cash.amount").value("0.00"))
                .andExpect(jsonPath("$.scenarios[0].divergence[2].date").value("2026-03-01"))
                .andExpect(jsonPath("$.scenarios[0].divergence[2].cash.amount").value("-60000.00"))
                .andExpect(
                        jsonPath("$.scenarios[0].divergence[2].invested.amount").value("0.00"))
                .andExpect(jsonPath("$.scenarios[0].divergence[2].total.amount").value("-60000.00"))
                .andExpect(jsonPath("$.scenarios[1].id").value(DO_NOTHING.id().toString()))
                .andExpect(jsonPath("$.scenarios[1].name").value("Do nothing"))
                .andExpect(jsonPath("$.scenarios[1].points.length()").value(4))
                .andExpect(jsonPath("$.scenarios[1].endState.date").value("2026-04-01"))
                .andExpect(jsonPath("$.scenarios[1].endState.cash.amount").value("20000.00"))
                .andExpect(jsonPath("$.scenarios[1].endState.total.amount").value("20000.00"))
                .andExpect(jsonPath("$.scenarios[1].divergence").isArray())
                .andExpect(jsonPath("$.scenarios[1].divergence.length()").value(0));
    }

    /** Checked before anything is projected: a divergence from a scenario not compared has no answer. */
    @Test
    void Given_a_baseline_that_is_not_among_the_scenarios_When_compared_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        mvc.perform(firstQuarter(ScenarioId.generate(), DO_NOTHING.id(), BUY_THE_CAR.id()))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(comparer, lister);
    }

    /**
     * Planning refuses an account named twice as the command is built, so neither in-port is called, and
     * the detail is the message the translation gave while it held the rule (ADR-051).
     */
    @Test
    void Given_an_account_named_twice_as_cash_When_compared_Then_it_is_a_422_rule_violated_problem() throws Exception {
        mvc.perform(firstQuarter(DO_NOTHING.id(), DO_NOTHING.id(), BUY_THE_CAR.id())
                        .param("cash", CURRENT_ACCOUNT, CURRENT_ACCOUNT))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail")
                        .value("Account reference " + CURRENT_ACCOUNT
                                + " is named more than once; it would be counted twice"))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(comparer, lister);
    }

    /**
     * The ids are in the query, so an unknown one is part of what the request asks for and its absence is
     * a refusal, not the 404 a projection's path gives (ADR-028).
     */
    @Test
    void Given_a_scenario_id_that_names_no_scenario_When_compared_Then_it_is_a_422_unknown_scenario_problem()
            throws Exception {
        ScenarioId unknown = ScenarioId.generate();
        ScenarioNotFoundException missing = new ScenarioNotFoundException(unknown);
        when(comparer.compare(any())).thenThrow(missing);

        mvc.perform(firstQuarter(DO_NOTHING.id(), DO_NOTHING.id(), unknown))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("UNKNOWN_SCENARIO"));

        verifyNoInteractions(lister);
    }

    /** A plan the store cannot find is a corrupt store, as in a projection (ADR-028). */
    @Test
    void Given_a_scenario_naming_a_plan_the_store_cannot_find_When_compared_Then_it_is_a_500_logged_not_served(
            CapturedOutput output) throws Exception {
        PlanId lost = PlanId.generate();
        when(comparer.compare(any())).thenThrow(new PlanNotFoundException(lost));

        String body = mvc.perform(firstQuarter(DO_NOTHING.id(), DO_NOTHING.id(), BUY_THE_CAR.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
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
     * Spring converts a blank {@code scenario} value to a null element rather than refusing it, whether the
     * value is repeated empty or left dangling after a comma, so the list still counts two and passes its
     * size check. The null is malformed, like an id that does not parse, and is refused before a {@code
     * ScenarioId} is built from it.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "?scenario=%1$s&scenario=&baseline=%1$s&from=2026-01-01&to=2026-04-01&step=MONTHLY",
                "?scenario=%1$s,&baseline=%1$s&from=2026-01-01&to=2026-04-01&step=MONTHLY"
            })
    void Given_a_blank_scenario_value_When_compared_Then_it_is_a_400_problem_naming_the_scenario_parameter(String query)
            throws Exception {
        mvc.perform(get(COMPARISONS + query.formatted(DO_NOTHING.id())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("scenario"));

        verifyNoInteractions(comparer, lister);
    }

    /** A blank baseline converts to null too, which Spring reports as a missing required parameter. */
    @Test
    void Given_a_blank_baseline_When_compared_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(get(COMPARISONS)
                        .param(
                                "scenario",
                                DO_NOTHING.id().toString(),
                                BUY_THE_CAR.id().toString())
                        .param("baseline", "")
                        .param("from", "2026-01-01")
                        .param("to", "2026-04-01")
                        .param("step", "MONTHLY"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(comparer, lister);
    }

    /**
     * A comparison of one is malformed on its face, like a transaction of one entry, so it is refused as a
     * 400 naming the parameter before anything is projected.
     */
    @Test
    void Given_a_single_scenario_When_compared_Then_it_is_a_400_problem_naming_the_scenario_parameter()
            throws Exception {
        mvc.perform(firstQuarter(DO_NOTHING.id(), DO_NOTHING.id()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("scenario"));

        verifyNoInteractions(comparer, lister);
    }

    /** Each scenario is a projection of its own, so the list is bounded as the horizon is. */
    @Test
    void Given_more_scenarios_than_the_maximum_When_compared_Then_it_is_a_400_problem_naming_the_scenario_parameter()
            throws Exception {
        ScenarioId[] tooMany = new ScenarioId[ComparisonController.MAXIMUM_SCENARIOS + 1];
        for (int index = 0; index < tooMany.length; index++) {
            tooMany[index] = ScenarioId.generate();
        }

        mvc.perform(firstQuarter(tooMany[0], tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("scenario"));

        verifyNoInteractions(comparer, lister);
    }

    @Test
    void Given_exactly_the_maximum_number_of_scenarios_When_compared_Then_it_is_answered_200_with_each_of_them()
            throws Exception {
        List<Scenario> most = new ArrayList<>();
        for (int index = 0; index < ComparisonController.MAXIMUM_SCENARIOS; index++) {
            most.add(Scenario.baseline("Strategy " + index, Currency.ILS, GrowthAssumption.none()));
        }
        ScenarioId[] ids = most.stream().map(Scenario::id).toArray(ScenarioId[]::new);
        when(comparer.compare(any())).thenReturn(comparisonOf(most.toArray(Scenario[]::new)));
        when(lister.all()).thenReturn(most);

        mvc.perform(firstQuarter(ids[0], ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios.length()").value(ComparisonController.MAXIMUM_SCENARIOS));
    }

    @Test
    void Given_a_horizon_one_day_beyond_the_maximum_span_When_compared_Then_it_is_a_422_and_nothing_is_projected()
            throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);

        mvc.perform(get(COMPARISONS)
                        .param(
                                "scenario",
                                DO_NOTHING.id().toString(),
                                BUY_THE_CAR.id().toString())
                        .param("baseline", DO_NOTHING.id().toString())
                        .param("from", from.toString())
                        .param(
                                "to",
                                from.plusYears(Horizon.MAXIMUM_SPAN_YEARS)
                                        .plusDays(1)
                                        .toString())
                        .param("step", "WEEKLY"))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(comparer, lister);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Two scenarios in this comparison are both named 'Do nothing'",
                "Scenario 'Holiday' is in USD but the comparison is in ILS; FinAssist performs no conversion",
                "A horizon of 2026-01-01 to 2026-02-15 is not a whole number of MONTHLY steps"
            })
    void Given_a_comparison_the_domain_refuses_When_compared_Then_it_is_a_422_rule_violated_problem(String refusal)
            throws Exception {
        when(comparer.compare(any())).thenThrow(new IllegalArgumentException(refusal));

        mvc.perform(firstQuarter(DO_NOTHING.id(), DO_NOTHING.id(), BUY_THE_CAR.id()))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(refusal))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(lister);
    }

    @Test
    void Given_a_horizon_that_does_not_start_before_it_ends_When_compared_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        mvc.perform(get(COMPARISONS)
                        .param(
                                "scenario",
                                DO_NOTHING.id().toString(),
                                BUY_THE_CAR.id().toString())
                        .param("baseline", DO_NOTHING.id().toString())
                        .param("from", "2026-04-01")
                        .param("to", "2026-01-01")
                        .param("step", "MONTHLY"))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(comparer, lister);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "baseline",
                "from",
                "to",
                "step",
            })
    void Given_a_required_parameter_is_missing_When_compared_Then_it_is_a_400_problem(String omitted) throws Exception {
        MockHttpServletRequestBuilder request = get(COMPARISONS)
                .param("scenario", DO_NOTHING.id().toString(), BUY_THE_CAR.id().toString());
        Map<String, String> required = new LinkedHashMap<>();
        required.put("baseline", DO_NOTHING.id().toString());
        required.put("from", "2026-01-01");
        required.put("to", "2026-04-01");
        required.put("step", "MONTHLY");
        required.remove(omitted);
        required.forEach(request::param);

        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(comparer, lister);
    }

    @Test
    void Given_a_malformed_scenario_id_When_compared_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(get(COMPARISONS)
                        .param("scenario", DO_NOTHING.id().toString(), "not-a-uuid")
                        .param("baseline", DO_NOTHING.id().toString())
                        .param("from", "2026-01-01")
                        .param("to", "2026-04-01")
                        .param("step", "MONTHLY"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(comparer, lister);
    }

    /** A comparison request over the first quarter, monthly, of the given scenarios against the baseline. */
    private static MockHttpServletRequestBuilder firstQuarter(ScenarioId baseline, ScenarioId... scenarios) {
        MockHttpServletRequestBuilder request = get(COMPARISONS)
                .param("baseline", baseline.toString())
                .param("from", "2026-01-01")
                .param("to", "2026-04-01")
                .param("step", "MONTHLY");
        for (ScenarioId scenario : scenarios) {
            request.param("scenario", scenario.toString());
        }
        return request;
    }

    /** The scenarios projected over the first quarter from 20000.00 cash and compared, in the order given. */
    private static Comparison comparisonOf(Scenario... scenarios) {
        Map<String, Projection> projections = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            projections.put(
                    scenario.name(),
                    Projection.of(
                            scenario,
                            FIRST_QUARTER,
                            Cadence.MONTHLY,
                            Money.of("20000.00", Currency.ILS),
                            Money.zero(Currency.ILS)));
        }
        return Comparison.of(projections);
    }
}
