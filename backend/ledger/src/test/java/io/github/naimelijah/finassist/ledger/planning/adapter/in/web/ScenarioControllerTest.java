package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.finassist.ledger.WebSliceSecurity;
import io.github.naimelijah.finassist.ledger.composition.StrictTextCoercion;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ListScenariosUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The scenario routes as a client sees them: status, content type, body and {@code code} (ADR-028).
 *
 * <p>A {@code @WebMvcTest} slice holds this controller, planning's advice and the composition fallback;
 * the in-ports are mocked, the same shape {@code TransactionControllerTest} uses. {@code PlanningHttpIT}
 * drives the same routes through the real services and the store.
 *
 * <p>Wire values are spelled out rather than borrowed from the adapter's constants, for the reason
 * {@code AccountControllerTest} gives.
 */
@WebMvcTest(controllers = ScenarioController.class)
@Import({WebSliceSecurity.class, StrictTextCoercion.class})
class ScenarioControllerTest {

    private static final String SCENARIOS = "/api/v1/scenarios";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private static final PlannedFlow CAR = PlannedFlow.plan(
            "Car",
            Money.of("60000.00", Currency.ILS),
            Direction.OUTFLOW,
            LocalDate.of(2026, 2, 15),
            new Recurrence.Once());

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DefineScenarioUseCase definer;

    @MockitoBean
    private ListScenariosUseCase lister;

    /** The port promises name order; the controller keeps whatever order it is given, not re-sort. */
    @Test
    void Given_scenarios_in_the_store_When_listed_Then_they_are_answered_200_in_the_port_order_with_their_flow_ids()
            throws Exception {
        Scenario doNothing = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());
        Scenario buyTheCar = Scenario.of("Buy the car", Currency.ILS, List.of(CAR), GrowthAssumption.perStep("0.005"));
        when(lister.all()).thenReturn(List.of(doNothing, buyTheCar));

        mvc.perform(get(SCENARIOS))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(doNothing.id().toString()))
                .andExpect(jsonPath("$[0].name").value("Do nothing"))
                .andExpect(jsonPath("$[0].currency").value("ILS"))
                .andExpect(jsonPath("$[0].flows").isArray())
                .andExpect(jsonPath("$[0].flows.length()").value(0))
                .andExpect(jsonPath("$[0].growthRatePerStep").value("0"))
                .andExpect(jsonPath("$[1].id").value(buyTheCar.id().toString()))
                .andExpect(jsonPath("$[1].flows.length()").value(1))
                .andExpect(jsonPath("$[1].flows[0]").value(CAR.id().toString()))
                .andExpect(jsonPath("$[1].growthRatePerStep").value("0.005"));
    }

    /**
     * {@code GrowthAssumption} strips trailing zeros, which is exactly what makes {@code BigDecimal}'s
     * {@code toString} reach for an exponent: {@code 1E-7} for a tiny rate and {@code 1E+1} for ten. The
     * wire carries plain decimal text, which the request pattern would accept back.
     */
    @ParameterizedTest
    @CsvSource({"0.005, 0.005", "0.00000010, 0.0000001", "10, 10", "-0.25, -0.25"})
    void Given_a_growth_rate_whose_normal_form_takes_an_exponent_When_listed_Then_it_is_plain_decimal_text(
            String rate, String rendered) throws Exception {
        when(lister.all())
                .thenReturn(List.of(Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.perStep(rate))));

        mvc.perform(get(SCENARIOS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].growthRatePerStep").value(rendered));
    }

    /** No {@code Location}: there is no single-item read for one to name (ADR-028). */
    @Test
    void Given_a_scenario_over_a_recorded_flow_When_defined_Then_it_is_answered_201_with_the_scenario_and_no_location()
            throws Exception {
        Scenario defined = Scenario.of("Buy the car", Currency.ILS, List.of(CAR), GrowthAssumption.perStep("0.005"));
        when(definer.define(new DefineScenarioCommand(
                        "Buy the car", Currency.ILS, List.of(CAR.id()), GrowthAssumption.perStep("0.005"))))
                .thenReturn(defined);

        mvc.perform(post(SCENARIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineRequest("Buy the car", "[\"" + CAR.id() + "\"]", "0.005")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(defined.id().toString()))
                .andExpect(jsonPath("$.name").value("Buy the car"))
                .andExpect(jsonPath("$.currency").value("ILS"))
                .andExpect(jsonPath("$.flows[0]").value(CAR.id().toString()))
                .andExpect(jsonPath("$.growthRatePerStep").value("0.005"));
    }

    /** The do-nothing baseline is a scenario with no flows, and a comparison needs one. */
    @Test
    void Given_a_scenario_with_no_flows_When_defined_Then_it_is_answered_201_as_a_baseline() throws Exception {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());
        when(definer.define(new DefineScenarioCommand("Do nothing", Currency.ILS, List.of(), GrowthAssumption.none())))
                .thenReturn(baseline);

        mvc.perform(post(SCENARIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineRequest("Do nothing", "[]", "0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flows").isArray())
                .andExpect(jsonPath("$.flows.length()").value(0));
    }

    /**
     * 404 is for the resource named in the path. A flow named in the body is part of what the request
     * asks for, so its absence is a refusal (ADR-028).
     */
    @Test
    void Given_a_flow_id_that_names_no_plan_When_defined_Then_it_is_a_422_unknown_plan_problem() throws Exception {
        PlanId unknown = PlanId.generate();
        PlanNotFoundException missing = new PlanNotFoundException(unknown);
        when(definer.define(any())).thenThrow(missing);

        mvc.perform(post(SCENARIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineRequest("Buy the car", "[\"" + unknown + "\"]", "0")))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("UNKNOWN_PLAN"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Flow 'Car' is in USD but the scenario is in ILS",
                "Flow 'Car' appears twice in this scenario; it would be counted twice"
            })
    void Given_flows_the_domain_refuses_When_defined_Then_it_is_a_422_rule_violated_problem(String refusal)
            throws Exception {
        when(definer.define(any())).thenThrow(new IllegalArgumentException(refusal));

        mvc.perform(post(SCENARIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineRequest("Buy the car", "[\"" + CAR.id() + "\"]", "0")))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(refusal))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
    }

    /** An exponent is not plain decimal text, so it is malformed before {@code GrowthAssumption} sees it. */
    @ParameterizedTest
    @ValueSource(strings = {"5e-3", "0.5%", "+0.005", "x0.005y"})
    void Given_a_growth_rate_that_is_not_plain_decimal_text_When_defined_Then_it_is_a_400_problem(String rate)
            throws Exception {
        mvc.perform(post(SCENARIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineRequest("Buy the car", "[]", rate)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("growthRatePerStep"));

        verifyNoInteractions(definer);
    }

    /** A growth rate is decimal text on the wire like an amount (ADR-028), so a JSON number is malformed. */
    @Test
    void Given_a_growth_rate_sent_as_a_json_number_When_defined_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(post(SCENARIOS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Buy the car", "currency": "ILS", "flows": [], "growthRatePerStep": 0.005}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(definer);
    }

    /** A rate at or below minus one would wipe out the balance; the domain refuses it as a rule. */
    @ParameterizedTest
    @ValueSource(strings = {"-1", "-1.5"})
    void Given_a_growth_rate_at_or_below_minus_one_When_defined_Then_it_is_a_422_rule_violated_problem(String rate)
            throws Exception {
        mvc.perform(post(SCENARIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineRequest("Buy the car", "[]", rate)))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(definer);
    }

    @Test
    void Given_a_blank_name_and_nothing_else_When_defined_Then_it_is_a_400_problem_listing_every_field()
            throws Exception {
        mvc.perform(post(SCENARIOS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": " "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("currency")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("flows")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("growthRatePerStep")));

        verifyNoInteractions(definer);
    }

    /** A null or a non-UUID in the list is malformed like a missing field, rather than a failure while mapping it. */
    @ParameterizedTest
    @ValueSource(strings = {"[null]", "[\"not-a-uuid\"]"})
    void Given_a_flow_list_holding_a_null_or_a_malformed_id_When_defined_Then_it_is_a_400_problem(String flows)
            throws Exception {
        mvc.perform(post(SCENARIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defineRequest("Buy the car", flows, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(definer);
    }

    /**
     * A cross-site page can post a form, plain text or multipart without a preflight, so a write refuses
     * anything but JSON (ADR-030).
     *
     * <p>MockMvc never parses a multipart body, so the multipart case here reaches the mapping's refusal
     * without the parsing a container performs first; {@code LedgerApplicationIT} posts a well-formed
     * multipart body to the running ledger.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                MediaType.TEXT_PLAIN_VALUE,
                MediaType.MULTIPART_FORM_DATA_VALUE
            })
    void Given_a_body_that_is_not_json_When_defining_a_scenario_Then_it_is_a_415_problem(String mediaType)
            throws Exception {
        mvc.perform(post(SCENARIOS).contentType(mediaType).content("name=Buy+the+car"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(definer);
    }

    private static String defineRequest(String name, String flows, String growthRatePerStep) {
        return """
                {"name": "%s", "currency": "ILS", "flows": %s, "growthRatePerStep": "%s"}
                """.formatted(name, flows, growthRatePerStep);
    }
}
