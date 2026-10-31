package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import static io.github.naimelijah.finassist.ledger.ProblemAssertions.requiredProblemMembers;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ListPlannedFlowsUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.TransitionPlanUseCase;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.IllegalPlanTransitionException;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The planned-flow routes as a client sees them: status, content type, body and {@code code} (ADR-028).
 *
 * <p>A {@code @WebMvcTest} slice holds this controller, planning's advice and the composition fallback;
 * the in-ports are mocked, the same shape {@code TransactionControllerTest} uses. Every recurrence kind
 * and every state is asserted member by member, because both sealed types are mapped by hand and a
 * member a variant lacks must be absent from the wire, not null. {@code PlanningHttpIT} drives the same
 * routes through the real services and the store.
 *
 * <p>Wire values are spelled out rather than borrowed from the adapter's constants, for the reason
 * {@code AccountControllerTest} gives.
 */
@WebMvcTest(controllers = PlannedFlowController.class)
@Import({WebSliceSecurity.class, StrictTextCoercion.class})
class PlannedFlowControllerTest {

    private static final String PLANNED_FLOWS = "/api/v1/planned-flows";
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    /** Every media type a cross-site page can post without a preflight (ADR-030). */
    private static final List<String> NOT_JSON = List.of(
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.TEXT_PLAIN_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE);

    /** Every write route, under every media type in {@link #NOT_JSON}, so a failure names both. */
    static Stream<Arguments> plannedFlowWritesInEveryMediaTypeButJson() {
        return Stream.of("", "/%s/commit", "/%s/realise", "/%s/cancel")
                .flatMap(route -> NOT_JSON.stream().map(mediaType -> Arguments.of(route, mediaType)));
    }

    private static final LocalDate JANUARY_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JANUARY_10 = LocalDate.of(2026, 1, 10);
    private static final LocalDate FEBRUARY_15 = LocalDate.of(2026, 2, 15);
    private static final LocalDate NEXT_JANUARY_1 = LocalDate.of(2027, 1, 1);
    private static final String REASON = "bought second-hand instead";

    /** Member names no request type declares, each a marker a response must never repeat. */
    private static final String UNKNOWN_MEMBER = "unknown-member-5e8c";

    private static final String UNKNOWN_NESTED_MEMBER = "unknown-nested-member-2a7f";

    private static final String ON_JANUARY_10 = """
            {"on": "2026-01-10"}
            """;
    private static final String CANCEL_ON_JANUARY_10 = """
            {"on": "2026-01-10", "reason": "bought second-hand instead"}
            """;
    private static final String ONCE = """
            {"kind": "ONCE"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlanFlowUseCase planner;

    @MockitoBean
    private ListPlannedFlowsUseCase lister;

    @MockitoBean
    private TransitionPlanUseCase lifecycle;

    /**
     * The port promises start-date order; the controller keeps whatever order it is given. Cancelled and
     * realised flows are listed with their dates, because they are history, not deletions.
     */
    @Test
    void Given_flows_in_every_state_When_listed_Then_they_are_answered_200_in_the_port_order_each_state_rendered()
            throws Exception {
        PlannedFlow planned = car(new PlanState.Planned());
        PlannedFlow committed = car(new PlanState.Committed(JANUARY_10));
        PlannedFlow realised = car(new PlanState.Realised(FEBRUARY_15));
        PlannedFlow cancelled = car(new PlanState.Cancelled(JANUARY_10, REASON));
        when(lister.all()).thenReturn(List.of(cancelled, realised, committed, planned));

        mvc.perform(get(PLANNED_FLOWS))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(cancelled.id().toString()))
                .andExpect(jsonPath("$[0].state.status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].state.on").value("2026-01-10"))
                .andExpect(jsonPath("$[0].state.reason").value(REASON))
                .andExpect(jsonPath("$[1].id").value(realised.id().toString()))
                .andExpect(jsonPath("$[1].state.status").value("REALISED"))
                .andExpect(jsonPath("$[1].state.on").value("2026-02-15"))
                .andExpect(jsonPath("$[1].state.reason").doesNotHaveJsonPath())
                .andExpect(jsonPath("$[2].id").value(committed.id().toString()))
                .andExpect(jsonPath("$[2].state.status").value("COMMITTED"))
                .andExpect(jsonPath("$[2].state.on").value("2026-01-10"))
                .andExpect(jsonPath("$[2].state.reason").doesNotHaveJsonPath())
                .andExpect(jsonPath("$[3].id").value(planned.id().toString()))
                .andExpect(jsonPath("$[3].state.status").value("PLANNED"))
                .andExpect(jsonPath("$[3].state.on").doesNotHaveJsonPath())
                .andExpect(jsonPath("$[3].state.reason").doesNotHaveJsonPath());
    }

    /**
     * No {@code Location}, as for a transaction. The outflow's amount stays positive on the wire: the
     * direction carries the sign, never the amount (ADR-015).
     */
    @Test
    void Given_a_once_off_outflow_When_planned_Then_it_is_answered_201_positive_once_and_planned_with_no_location()
            throws Exception {
        PlannedFlow planned = car(new PlanState.Planned());
        when(planner.plan(new PlanFlowCommand(
                        "Car", ils("60000.00"), Direction.OUTFLOW, FEBRUARY_15, new Recurrence.Once())))
                .thenReturn(planned);

        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content(carRequest("60000.00", ONCE)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(planned.id().toString()))
                .andExpect(jsonPath("$.description").value("Car"))
                .andExpect(jsonPath("$.amount.amount").value("60000.00"))
                .andExpect(jsonPath("$.amount.currency").value("ILS"))
                .andExpect(jsonPath("$.direction").value("OUTFLOW"))
                .andExpect(jsonPath("$.startsOn").value("2026-02-15"))
                .andExpect(jsonPath("$.recurrence.kind").value("ONCE"))
                .andExpect(jsonPath("$.recurrence.cadence").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.recurrence.interval").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.recurrence.until").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.recurrence.count").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.state.status").value("PLANNED"))
                .andExpect(jsonPath("$.state.on").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.state.reason").doesNotHaveJsonPath());
    }

    @Test
    void Given_a_monthly_inflow_until_a_date_When_planned_Then_it_is_answered_201_with_cadence_interval_and_until_only()
            throws Exception {
        Recurrence untilNextYear = new Recurrence.Until(Cadence.MONTHLY, 1, NEXT_JANUARY_1);
        PlannedFlow salary = new PlannedFlow(
                PlanId.generate(),
                "Salary",
                ils("10000.00"),
                Direction.INFLOW,
                JANUARY_1,
                untilNextYear,
                new PlanState.Planned());
        when(planner.plan(new PlanFlowCommand("Salary", ils("10000.00"), Direction.INFLOW, JANUARY_1, untilNextYear)))
                .thenReturn(salary);

        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"description": "Salary", "amount": {"amount": "10000.00", "currency": "ILS"},
                         "direction": "INFLOW", "startsOn": "2026-01-01",
                         "recurrence": {"kind": "UNTIL", "cadence": "MONTHLY", "interval": 1, "until": "2027-01-01"}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("INFLOW"))
                .andExpect(jsonPath("$.recurrence.kind").value("UNTIL"))
                .andExpect(jsonPath("$.recurrence.cadence").value("MONTHLY"))
                .andExpect(jsonPath("$.recurrence.interval").value(1))
                .andExpect(jsonPath("$.recurrence.until").value("2027-01-01"))
                .andExpect(jsonPath("$.recurrence.count").doesNotHaveJsonPath());
    }

    @Test
    void
            Given_a_contribution_a_number_of_times_When_planned_Then_it_is_answered_201_with_cadence_interval_and_count_only()
                    throws Exception {
        Recurrence twelveTimes = new Recurrence.Times(Cadence.WEEKLY, 2, 12);
        PlannedFlow contribution = new PlannedFlow(
                PlanId.generate(),
                "Index fund",
                ils("500.00"),
                Direction.INVESTMENT,
                JANUARY_1,
                twelveTimes,
                new PlanState.Planned());
        when(planner.plan(
                        new PlanFlowCommand("Index fund", ils("500.00"), Direction.INVESTMENT, JANUARY_1, twelveTimes)))
                .thenReturn(contribution);

        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"description": "Index fund", "amount": {"amount": "500.00", "currency": "ILS"},
                         "direction": "INVESTMENT", "startsOn": "2026-01-01",
                         "recurrence": {"kind": "TIMES", "cadence": "WEEKLY", "interval": 2, "count": 12}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("INVESTMENT"))
                .andExpect(jsonPath("$.recurrence.kind").value("TIMES"))
                .andExpect(jsonPath("$.recurrence.cadence").value("WEEKLY"))
                .andExpect(jsonPath("$.recurrence.interval").value(2))
                .andExpect(jsonPath("$.recurrence.count").value(12))
                .andExpect(jsonPath("$.recurrence.until").doesNotHaveJsonPath());
    }

    /** A recurrence that cannot be built is malformed, like a missing field, and never reaches the port. */
    @Test
    void Given_a_times_recurrence_missing_its_count_When_planned_Then_it_is_a_400_problem_naming_the_recurrence()
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content(carRequest("60000.00", """
                                {"kind": "TIMES", "cadence": "MONTHLY", "interval": 1}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("recurrence.completeForKind")));

        verifyNoInteractions(planner);
    }

    /** Each kind carries exactly its own members: one missing, or one belonging to another kind, is malformed. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\": \"ONCE\", \"cadence\": \"MONTHLY\"}",
                "{\"kind\": \"ONCE\", \"count\": 1}",
                "{\"kind\": \"UNTIL\", \"cadence\": \"MONTHLY\", \"interval\": 1}",
                "{\"kind\": \"UNTIL\", \"interval\": 1, \"until\": \"2027-01-01\"}",
                "{\"kind\": \"UNTIL\", \"cadence\": \"MONTHLY\", \"interval\": 1, \"until\": \"2027-01-01\", \"count\": 12}",
                "{\"kind\": \"TIMES\", \"cadence\": \"MONTHLY\", \"count\": 12}",
                "{\"kind\": \"TIMES\", \"cadence\": \"MONTHLY\", \"interval\": 1, \"count\": 12, \"until\": \"2027-01-01\"}"
            })
    void Given_a_recurrence_whose_members_do_not_fit_its_kind_When_planned_Then_it_is_a_400_problem(String recurrence)
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carRequest("60000.00", recurrence)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("recurrence.completeForKind")));

        verifyNoInteractions(planner);
    }

    /** A missing kind is reported once, as the kind, rather than also as a recurrence that fits no kind. */
    @Test
    void Given_a_recurrence_with_no_kind_When_planned_Then_it_is_a_400_problem_naming_only_the_kind() throws Exception {
        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content(carRequest("60000.00", "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("recurrence.kind"));

        verifyNoInteractions(planner);
    }

    @Test
    void Given_a_blank_description_and_no_direction_When_planned_Then_it_is_a_400_problem_listing_both()
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"description": " ", "amount": {"amount": "60000.00", "currency": "ILS"},
                         "startsOn": "2026-02-15", "recurrence": {"kind": "ONCE"}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("description")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("direction")));

        verifyNoInteractions(planner);
    }

    /**
     * A fraction is not an integer. Jackson would otherwise truncate it into {@code interval} or {@code
     * count}, and plan a recurrence the client never sent: 1.5 months would become one. {@code 1.0} is
     * refused too: it is the decimal-pointed whole number most likely to be coerced without anyone noticing.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\": \"UNTIL\", \"cadence\": \"MONTHLY\", \"interval\": 1.5, \"until\": \"2027-01-01\"}",
                "{\"kind\": \"UNTIL\", \"cadence\": \"MONTHLY\", \"interval\": 1.0, \"until\": \"2027-01-01\"}",
                "{\"kind\": \"TIMES\", \"cadence\": \"MONTHLY\", \"interval\": 1, \"count\": 12.5}"
            })
    void Given_an_interval_or_count_with_a_decimal_point_When_planned_Then_it_is_a_400_problem(String recurrence)
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carRequest("60000.00", recurrence)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(planner);
    }

    /**
     * An interval past what an integer holds is valid JSON naming a known member, so the problem names that
     * member, as it would any value Jackson refuses, rather than calling the body unparseable.
     */
    @Test
    void Given_an_interval_too_large_for_an_integer_When_planned_Then_it_is_a_400_problem_naming_the_interval()
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content(carRequest("60000.00", """
                                {"kind": "UNTIL", "cadence": "MONTHLY", "interval": 3000000000, "until": "2027-01-01"}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("recurrence.interval"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a valid value"));

        verifyNoInteractions(planner);
    }

    /**
     * A problem's {@code field} is always a name the ledger declares, never one the caller chose (ADR-068).
     * An unknown member is ignored, so the 400 names only the known member that broke, and neither unknown
     * name is repeated anywhere in it.
     *
     * <p>The known member breaks a bean-validation constraint on purpose. Validation runs only once Jackson
     * has read the whole body, so a mapper that refused unknown members would fail first and name one; a
     * member Jackson itself refuses would not do, because Jackson builds a record before it handles the
     * unknown members it set aside, and would report the known member either way.
     */
    @Test
    void Given_unknown_members_beside_an_invalid_known_member_When_planned_Then_the_400_names_only_the_known_member()
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"%s": "x", "description": " ", "amount": {"amount": "60000.00", "currency": "ILS"},
                         "direction": "OUTFLOW", "startsOn": "2026-02-15", "recurrence": {"%s": "x", "kind": "ONCE"}}
                        """.formatted(
                                UNKNOWN_MEMBER, UNKNOWN_NESTED_MEMBER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(content()
                        .string(allOf(
                                not(containsString(UNKNOWN_MEMBER)), not(containsString(UNKNOWN_NESTED_MEMBER)))));

        verifyNoInteractions(planner);
    }

    /**
     * A recurrence's numbers are the domain's to refuse, as the amount's sign is: a well-formed request
     * breaking a rule is 422, and it is refused before the port is called.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\": \"UNTIL\", \"cadence\": \"MONTHLY\", \"interval\": 0, \"until\": \"2027-01-01\"}",
                "{\"kind\": \"TIMES\", \"cadence\": \"MONTHLY\", \"interval\": 1, \"count\": 0}"
            })
    void Given_a_recurrence_with_a_non_positive_interval_or_count_When_planned_Then_it_is_a_422_rule_violated_problem(
            String recurrence) throws Exception {
        mvc.perform(post(PLANNED_FLOWS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carRequest("60000.00", recurrence)))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(planner);
    }

    /** The adapter never flips a sign: a negative amount reaches the domain as sent, and the domain refuses it. */
    @Test
    void Given_a_non_positive_amount_When_planned_Then_it_is_a_422_rule_violated_problem() throws Exception {
        IllegalArgumentException negative = new IllegalArgumentException(
                "A planned flow must be for a positive amount but was -60000.00 ILS; the direction carries the sign");
        when(planner.plan(new PlanFlowCommand(
                        "Car", ils("-60000.00"), Direction.OUTFLOW, FEBRUARY_15, new Recurrence.Once())))
                .thenThrow(negative);

        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content(carRequest("-60000.00", ONCE)))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(negative.getMessage()))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
    }

    @Test
    void Given_a_recurrence_ending_before_it_starts_When_planned_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        when(planner.plan(any()))
                .thenThrow(new IllegalArgumentException(
                        "A recurrence cannot end on 2026-01-01, before it starts on 2026-02-15; it would never occur"));

        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content(carRequest("60000.00", """
                                {"kind": "UNTIL", "cadence": "MONTHLY", "interval": 1, "until": "2026-01-01"}
                                """)))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
    }

    /** An amount is decimal text on the wire (ADR-028), so a JSON number is malformed rather than coerced. */
    @Test
    void Given_an_amount_sent_as_a_json_number_When_planned_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content("""
                        {"description": "Car", "amount": {"amount": 60000.00, "currency": "ILS"},
                         "direction": "OUTFLOW", "startsOn": "2026-02-15", "recurrence": {"kind": "ONCE"}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(planner);
    }

    @Test
    void Given_an_amount_finer_than_its_currency_admits_When_planned_Then_it_is_a_422_rule_violated_problem()
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS).contentType(MediaType.APPLICATION_JSON).content(carRequest("60000.005", ONCE)))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));

        verifyNoInteractions(planner);
    }

    @Test
    void Given_a_planned_flow_When_committed_Then_it_is_answered_200_committed_on_that_date_with_no_reason()
            throws Exception {
        PlannedFlow committed = car(new PlanState.Committed(JANUARY_10));
        when(lifecycle.commit(committed.id(), JANUARY_10)).thenReturn(committed);

        mvc.perform(post(PLANNED_FLOWS + "/" + committed.id() + "/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ON_JANUARY_10))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(committed.id().toString()))
                .andExpect(jsonPath("$.amount.amount").value("60000.00"))
                .andExpect(jsonPath("$.state.status").value("COMMITTED"))
                .andExpect(jsonPath("$.state.on").value("2026-01-10"))
                .andExpect(jsonPath("$.state.reason").doesNotHaveJsonPath());
    }

    @Test
    void Given_a_committed_flow_When_realised_Then_it_is_answered_200_realised_on_that_date_with_no_reason()
            throws Exception {
        PlannedFlow realised = car(new PlanState.Realised(JANUARY_10));
        when(lifecycle.realise(realised.id(), JANUARY_10)).thenReturn(realised);

        mvc.perform(post(PLANNED_FLOWS + "/" + realised.id() + "/realise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ON_JANUARY_10))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state.status").value("REALISED"))
                .andExpect(jsonPath("$.state.on").value("2026-01-10"))
                .andExpect(jsonPath("$.state.reason").doesNotHaveJsonPath());
    }

    @Test
    void Given_a_planned_flow_When_cancelled_Then_it_is_answered_200_cancelled_with_its_date_and_reason()
            throws Exception {
        PlannedFlow cancelled = car(new PlanState.Cancelled(JANUARY_10, REASON));
        when(lifecycle.cancel(cancelled.id(), JANUARY_10, REASON)).thenReturn(cancelled);

        mvc.perform(post(PLANNED_FLOWS + "/" + cancelled.id() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CANCEL_ON_JANUARY_10))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state.status").value("CANCELLED"))
                .andExpect(jsonPath("$.state.on").value("2026-01-10"))
                .andExpect(jsonPath("$.state.reason").value(REASON));
    }

    /** The plan is named in the path, so its absence is 404 (ADR-028). */
    @ParameterizedTest
    @MethodSource("lifecycleCommands")
    void Given_an_unknown_plan_When_a_lifecycle_command_is_sent_Then_it_is_a_404_plan_not_found_problem(
            String command, String body) throws Exception {
        PlanId unknown = PlanId.generate();
        PlanNotFoundException missing = new PlanNotFoundException(unknown);
        everyTransitionThrows(missing);

        mvc.perform(post(PLANNED_FLOWS + "/" + unknown + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(missing.getMessage()))
                .andExpect(jsonPath("$.code").value("PLAN_NOT_FOUND"))
                .andExpect(requiredProblemMembers());
    }

    @ParameterizedTest
    @MethodSource("lifecycleCommands")
    void Given_a_state_forbidding_it_When_a_lifecycle_command_is_sent_Then_it_is_a_409_illegal_transition_problem(
            String command, String body) throws Exception {
        IllegalPlanTransitionException illegal =
                new IllegalPlanTransitionException(new PlanState.Realised(FEBRUARY_15), command);
        everyTransitionThrows(illegal);

        mvc.perform(post(PLANNED_FLOWS + "/" + PlanId.generate() + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value(illegal.getMessage()))
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"))
                .andExpect(requiredProblemMembers());
    }

    /** Realising, or cancelling a committed plan, on a date before its commitment is a rule, not a state. */
    @ParameterizedTest
    @ValueSource(strings = {"realise", "cancel"})
    void Given_a_date_before_the_commitment_When_a_lifecycle_command_is_sent_Then_it_is_a_422_rule_violated_problem(
            String command) throws Exception {
        everyTransitionThrows(new IllegalArgumentException(
                "A plan cannot be realised on 2026-01-10, before it was committed on 2026-01-11"));

        mvc.perform(post(PLANNED_FLOWS + "/" + PlanId.generate() + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CANCEL_ON_JANUARY_10))
                .andExpect(status().is(422))
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RULE_VIOLATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"commit", "realise", "cancel"})
    void Given_no_date_When_a_lifecycle_command_is_sent_Then_it_is_a_400_problem_listing_the_date(String command)
            throws Exception {
        mvc.perform(post(PLANNED_FLOWS + "/" + PlanId.generate() + "/" + command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"reason": "bought second-hand instead"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("on")));

        verifyNoInteractions(lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"on\": \"2026-01-10\"}", "{\"on\": \"2026-01-10\", \"reason\": \" \"}"})
    void Given_a_cancel_with_a_missing_or_blank_reason_When_sent_Then_it_is_a_400_problem_listing_the_reason(
            String body) throws Exception {
        mvc.perform(post(PLANNED_FLOWS + "/" + PlanId.generate() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));

        verifyNoInteractions(lifecycle);
    }

    @Test
    void Given_a_malformed_plan_id_When_committed_Then_it_is_a_400_problem() throws Exception {
        mvc.perform(post(PLANNED_FLOWS + "/not-a-uuid/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ON_JANUARY_10))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(lifecycle);
    }

    /**
     * Every write refuses a form, plain text or multipart post, which a cross-site page can send without a
     * preflight (ADR-030).
     *
     * <p>MockMvc never parses a multipart body, so the multipart case here reaches the mapping's refusal
     * without the parsing a container performs first; {@code LedgerApplicationIT} posts a well-formed
     * multipart body to the running ledger.
     */
    @ParameterizedTest
    @MethodSource("plannedFlowWritesInEveryMediaTypeButJson")
    void Given_a_body_that_is_not_json_When_any_planned_flow_write_is_posted_Then_it_is_a_415_problem(
            String route, String mediaType) throws Exception {
        mvc.perform(post(PLANNED_FLOWS + route.formatted(PlanId.generate()))
                        .contentType(mediaType)
                        .content("on=2026-01-10"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(planner, lifecycle);
    }

    /** Each lifecycle command with a body it accepts, so only the port's answer differs between cases. */
    static Stream<Arguments> lifecycleCommands() {
        return Stream.of(
                Arguments.of("commit", ON_JANUARY_10),
                Arguments.of("realise", ON_JANUARY_10),
                Arguments.of("cancel", CANCEL_ON_JANUARY_10));
    }

    private void everyTransitionThrows(RuntimeException failure) {
        when(lifecycle.commit(any(), any())).thenThrow(failure);
        when(lifecycle.realise(any(), any())).thenThrow(failure);
        when(lifecycle.cancel(any(), any(), any())).thenThrow(failure);
    }

    /** A request to plan a car on 15 February, for the given amount and recurrence. */
    private static String carRequest(String amount, String recurrence) {
        return """
                {"description": "Car", "amount": {"amount": "%s", "currency": "ILS"}, "direction": "OUTFLOW",
                 "startsOn": "2026-02-15", "recurrence": %s}
                """.formatted(amount, recurrence);
    }

    /** A once-off car purchase of 60000.00 on 15 February, in the given state. */
    private static PlannedFlow car(PlanState state) {
        return new PlannedFlow(
                PlanId.generate(),
                "Car",
                ils("60000.00"),
                Direction.OUTFLOW,
                FEBRUARY_15,
                new Recurrence.Once(),
                state);
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
