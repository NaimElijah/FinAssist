package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What planning's two repositories promise, asserted against the store.
 *
 * <p>Two claims need a database. The first is that {@code Recurrence} and {@code PlanState} — three
 * and four variants of a sealed interface — survive a round trip through a discriminator and a
 * handful of nullable columns, which is the mapping most likely to be silently wrong. The second is
 * that a scenario stores its flows <em>by identity</em>: {@code ForecastService} re-reads each one
 * before projecting precisely because the copy embedded in the scenario goes stale, and storing the
 * copy would make the stale value durable.
 */
@SpringBootTest
class JdbcPlanningRepositoriesIT extends PostgresFixture {

    private static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate FEB_10 = LocalDate.of(2026, 2, 10);
    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate NEXT_YEAR = LocalDate.of(2027, 2, 1);

    /** PostgreSQL compares {@code uuid} byte by byte, so these two order the way they read. */
    private static final UUID SMALLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID LARGER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Blank to {@code String.isBlank}, and not to PostgreSQL's {@code btrim}, which strips spaces alone. */
    private static final String TAB = "\t";

    @Autowired
    private PlannedFlowRepository flows;

    @Autowired
    private ScenarioRepository scenarios;

    /** Every shape the sealed {@code Recurrence} can take. A variant added here fails to compile. */
    static List<Recurrence> everyRecurrence() {
        return List.of(
                new Recurrence.Once(),
                new Recurrence.Until(Cadence.MONTHLY, 1, NEXT_YEAR),
                new Recurrence.Times(Cadence.WEEKLY, 2, 12),
                new Recurrence.Times(Cadence.YEARLY, 3, 5));
    }

    @ParameterizedTest
    @MethodSource("everyRecurrence")
    void Given_a_recurrence_When_the_flow_is_read_back_Then_it_is_the_same_recurrence(Recurrence recurrence) {
        PlannedFlow flow = PlannedFlow.plan("New car", ils("40000.00"), Direction.OUTFLOW, FEB_1, recurrence);

        flows.save(flow);

        assertThat(flows.findById(flow.id())).contains(flow);
    }

    /** Every shape the sealed {@code PlanState} can take, reached the way the domain reaches it. */
    static List<PlannedFlow> everyState() {
        PlannedFlow planned =
                PlannedFlow.plan("New car", ils("40000.00"), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        return List.of(
                planned,
                planned.commit(FEB_10),
                planned.commit(FEB_10).realise(MARCH_1),
                planned.cancel(FEB_10, "too expensive"));
    }

    @ParameterizedTest
    @MethodSource("everyState")
    void Given_a_plan_state_When_the_flow_is_read_back_Then_it_is_the_same_state(PlannedFlow flow) {
        flows.save(flow);

        assertThat(flows.findById(flow.id())).contains(flow);
    }

    @Test
    void Given_no_such_flow_When_it_is_looked_up_Then_nothing_is_found() {
        assertThat(flows.findById(PlanId.generate())).isEmpty();
        assertThat(scenarios.findById(ScenarioId.generate())).isEmpty();
    }

    /**
     * A transition is stored by saving the aggregate in its new state, and the plan is kept rather
     * than removed — the port has no delete, and a cancelled plan is history a projection excludes.
     */
    @Test
    void Given_a_cancelled_plan_When_it_is_read_back_Then_it_is_kept_with_its_reason() {
        PlannedFlow planned =
                PlannedFlow.plan("New car", ils("40000.00"), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        flows.save(planned);

        flows.save(planned.cancel(FEB_10, "too expensive"));

        PlannedFlow stored = flows.findById(planned.id()).orElseThrow();
        assertThat(stored.countsTowardsProjection()).isFalse();
        assertThat(stored.state())
                .isEqualTo(new io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState.Cancelled(
                        FEB_10, "too expensive"));
    }

    /**
     * The flows are listed in another order than they were stored in, so a read that followed the plans'
     * storage order rather than the scenario's own would fail this.
     */
    @Test
    void Given_a_scenario_over_flows_When_it_is_read_back_Then_its_flows_come_back_in_order() {
        PlannedFlow car = stored("New car", "40000.00");
        PlannedFlow tuition = stored("Tuition", "20000.00");
        PlannedFlow holiday = stored("Holiday", "8000.00");
        Scenario scenario =
                Scenario.of("Spend it all", Currency.ILS, List.of(holiday, car, tuition), GrowthAssumption.none());

        scenarios.save(scenario);

        assertThat(scenarios.findById(scenario.id())).contains(scenario);
    }

    /**
     * Each link row carries a currency the keys tie to its plan and its strategy, and the adapter writes
     * it. A strategy in a currency other than ILS must still save and come back whole.
     */
    @Test
    void Given_a_usd_scenario_over_a_usd_flow_When_it_is_saved_and_read_back_Then_it_is_the_same_scenario() {
        PlannedFlow car = PlannedFlow.plan(
                "New car", Money.of("40000.00", Currency.USD), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        flows.save(car);
        Scenario scenario = Scenario.of("Buy the car", Currency.USD, List.of(car), GrowthAssumption.none());

        scenarios.save(scenario);

        assertThat(scenarios.findById(scenario.id())).contains(scenario);
    }

    /** A comparison needs a do-nothing baseline, so an empty flow list has to survive the round trip. */
    @Test
    void Given_a_baseline_scenario_When_it_is_read_back_Then_it_still_names_no_flows() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        scenarios.save(baseline);

        assertThat(scenarios.findById(baseline.id())).contains(baseline);
    }

    /**
     * The reason the relation is stored by identity. A flow cancelled after the scenario was named
     * must come back cancelled; a scenario that had stored its flows by value would hand back the
     * state they had when it was written, and forecast a commitment the owner had already dropped.
     */
    @Test
    void Given_a_flow_cancelled_after_the_scenario_was_named_When_the_scenario_is_read_Then_it_is_cancelled() {
        PlannedFlow car = stored("New car", "40000.00");
        Scenario scenario = Scenario.of("Buy the car", Currency.ILS, List.of(car), GrowthAssumption.none());
        scenarios.save(scenario);

        flows.save(car.cancel(FEB_10, "too expensive"));

        assertThat(scenarios.findById(scenario.id()).orElseThrow().forecastableFlows())
                .isEmpty();
    }

    /**
     * {@code GrowthAssumption} normalises with {@code stripTrailingZeros}, and a record's generated
     * equals compares {@code BigDecimal} by scale. A rate of 100 is held as {@code 1E+2}; stored as
     * {@code numeric} it comes back at scale 0, and only the constructor re-normalising makes the two
     * equal. This is the round trip most likely to break silently.
     */
    @Test
    void Given_a_growth_rate_whose_scale_is_normalised_When_read_back_Then_it_is_still_equal() {
        Scenario aggressive = Scenario.baseline("Very aggressive", Currency.ILS, GrowthAssumption.perStep("100.000"));

        scenarios.save(aggressive);

        assertThat(scenarios.findById(aggressive.id())).contains(aggressive);
        assertThat(scenarios.findById(aggressive.id()).orElseThrow().growth())
                .isEqualTo(GrowthAssumption.perStep("100"));
    }

    @Test
    void Given_a_fractional_growth_rate_When_read_back_Then_it_is_still_equal() {
        Scenario modest = Scenario.baseline("Modest", Currency.ILS, GrowthAssumption.perStep("0.0400"));

        scenarios.save(modest);

        assertThat(scenarios.findById(modest.id())).contains(modest);
    }

    /** {@code PlannedFlowRepository.all}'s contract: start date, then id. */
    @Test
    void Given_flows_with_different_start_dates_When_all_are_listed_Then_they_come_back_ordered_by_start_date() {
        PlannedFlow later = PlannedFlow.plan("Later", ils("100.00"), Direction.OUTFLOW, MARCH_1, new Recurrence.Once());
        PlannedFlow earlier =
                PlannedFlow.plan("Earlier", ils("50.00"), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        flows.save(later);
        flows.save(earlier);

        assertThat(flows.all()).containsExactly(earlier, later);
    }

    /**
     * The port's tie-break: two flows starting on one date come back by id. They are saved larger id
     * first, so a store that returned them in insertion order would fail this.
     */
    @Test
    void Given_two_flows_starting_on_one_date_saved_larger_id_first_When_all_are_listed_Then_the_smaller_id_leads() {
        PlannedFlow larger = new PlannedFlow(
                new PlanId(LARGER_ID),
                "Second by id",
                ils("100.00"),
                Direction.OUTFLOW,
                FEB_1,
                new Recurrence.Once(),
                new PlanState.Planned());
        PlannedFlow smaller = new PlannedFlow(
                new PlanId(SMALLER_ID),
                "First by id",
                ils("50.00"),
                Direction.OUTFLOW,
                FEB_1,
                new Recurrence.Once(),
                new PlanState.Planned());
        flows.save(larger);
        flows.save(smaller);

        assertThat(flows.all()).containsExactly(smaller, larger);
    }

    /** A cancelled flow is history, not something a listing hides - the port makes no state exception. */
    @Test
    void Given_a_cancelled_flow_When_all_are_listed_Then_it_is_still_returned() {
        PlannedFlow planned =
                PlannedFlow.plan("New car", ils("40000.00"), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        flows.save(planned);
        PlannedFlow cancelled = planned.cancel(FEB_10, "too expensive");
        flows.save(cancelled);

        assertThat(flows.all()).containsExactly(cancelled);
    }

    @Test
    void Given_no_flows_at_all_When_they_are_listed_Then_the_answer_is_empty_rather_than_null() {
        assertThat(flows.all()).isEmpty();
    }

    /** {@code ScenarioRepository.all}'s contract: name, then id. */
    @Test
    void Given_scenarios_with_different_names_When_all_are_listed_Then_they_come_back_ordered_by_name() {
        Scenario zeta = Scenario.baseline("Zeta", Currency.ILS, GrowthAssumption.none());
        Scenario alpha = Scenario.baseline("Alpha", Currency.ILS, GrowthAssumption.none());
        scenarios.save(zeta);
        scenarios.save(alpha);

        assertThat(scenarios.all()).containsExactly(alpha, zeta);
    }

    /**
     * Names are not unique, so the port breaks a tie by id. The two are saved larger id first, so a store
     * that returned them in insertion order would fail this.
     */
    @Test
    void Given_two_scenarios_sharing_a_name_saved_larger_id_first_When_all_are_listed_Then_the_smaller_id_leads() {
        Scenario larger =
                new Scenario(new ScenarioId(LARGER_ID), "Plan A", Currency.ILS, List.of(), GrowthAssumption.none());
        Scenario smaller =
                new Scenario(new ScenarioId(SMALLER_ID), "Plan A", Currency.ILS, List.of(), GrowthAssumption.none());
        scenarios.save(larger);
        scenarios.save(smaller);

        assertThat(scenarios.all()).containsExactly(smaller, larger);
    }

    @Test
    void Given_no_scenarios_at_all_When_they_are_listed_Then_the_answer_is_empty_rather_than_null() {
        assertThat(scenarios.all()).isEmpty();
    }

    /** The flows of a listed scenario are loaded, the same mapping {@code findById} uses. */
    @Test
    void Given_a_scenario_over_flows_When_all_are_listed_Then_its_flows_are_loaded() {
        PlannedFlow car = stored("New car", "40000.00");
        Scenario scenario = Scenario.of("Spend it all", Currency.ILS, List.of(car), GrowthAssumption.none());
        scenarios.save(scenario);

        assertThat(scenarios.all()).containsExactly(scenario);
    }

    @Test
    void Given_a_flow_When_read_over_an_independent_connection_Then_it_is_still_there() {
        PlannedFlow car = stored("New car", "40000.00");

        PlannedFlowRepository elsewhere = new JdbcPlannedFlowRepository(freshConnection());

        assertThat(elsewhere.findById(car.id())).contains(car);
    }

    /**
     * {@code numeric} keeps whatever scale it is given, and {@link Money} refuses more precision than a
     * currency admits. Reading such a row is a server fault naming the column, not an {@code
     * IllegalArgumentException} a problem handler would answer as a broken rule.
     *
     * <p>The fault is logged at ERROR, and ECS prints every cause's message into {@code stack_trace}, so it
     * names the flow by its id and carries neither the amount nor {@code Money}'s refusal, which repeats it.
     */
    @Test
    void Given_a_flow_stored_with_more_precision_than_its_currency_admits_When_read_Then_it_is_a_server_fault() {
        PlanId id = PlanId.generate();
        jdbc().sql("""
                        INSERT INTO planning.planned_flow
                            (id, description, amount, currency, direction, starts_on, recurrence, state)
                        VALUES (:id, 'Too precise', 40000.005, 'ILS', 'OUTFLOW', :startsOn, 'ONCE', 'PLANNED')
                        """).param("id", id.value()).param("startsOn", FEB_1).update();

        assertThatThrownBy(() -> flows.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning.planned_flow.amount")
                .hasMessageContaining(id.value().toString())
                .hasNoCause()
                .message()
                .doesNotContain("40000.005");
    }

    /**
     * Every flow in a strategy is in the strategy's currency, held by the database as well as by {@code
     * Scenario}. The link row carries a currency that two composite foreign keys tie to both ends, so it
     * cannot agree with one and not the other. Listed in the scenario's currency, it is refused by the
     * key to the plan.
     */
    @Test
    void
            Given_a_scenario_in_another_currency_than_a_flow_When_listed_in_the_scenarios_currency_Then_the_database_refuses_it() {
        PlannedFlow car = stored("New car", "40000.00");
        ScenarioId id = scenarioStoredDirectly("USD");

        assertThatThrownBy(() -> listDirectly(id, car, "USD")).hasMessageContaining("scenario_flow_in_plan_currency");
    }

    /** The same link listed in the plan's currency instead: refused by the key to the scenario. */
    @Test
    void
            Given_a_scenario_in_another_currency_than_a_flow_When_listed_in_the_flows_currency_Then_the_database_refuses_it() {
        PlannedFlow car = stored("New car", "40000.00");
        ScenarioId id = scenarioStoredDirectly("USD");

        assertThatThrownBy(() -> listDirectly(id, car, "ILS"))
                .hasMessageContaining("scenario_flow_in_scenario_currency");
    }

    /**
     * The key from the plan's side: a plan a strategy lists cannot move to another currency. No
     * transition changes a plan's amount, so only a writer outside the adapter could try.
     */
    @Test
    void Given_a_flow_a_scenario_lists_When_its_currency_is_changed_directly_Then_the_database_refuses_it() {
        PlannedFlow car = stored("New car", "40000.00");
        scenarios.save(Scenario.of("Buy the car", Currency.ILS, List.of(car), GrowthAssumption.none()));

        assertThatThrownBy(() -> jdbc().sql("UPDATE planning.planned_flow SET currency = 'USD' WHERE id = :id")
                        .param("id", car.id().value())
                        .update())
                .hasMessageContaining("scenario_flow_in_plan_currency");
    }

    /**
     * {@code Scenario} still checks every rule as a stored row is read back, and the schema states one of
     * them less strictly: {@code btrim} strips spaces alone, so a name of a tab passes its check and not
     * {@code String.isBlank}. A stored scenario the aggregate refuses is a server fault naming the
     * scenario, not a rule a caller broke.
     */
    @Test
    void Given_a_scenario_stored_with_a_name_only_java_counts_as_blank_When_read_Then_it_is_a_server_fault() {
        ScenarioId id = scenarioStoredDirectly(TAB, "ILS");

        assertThatThrownBy(() -> scenarios.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planning.scenario")
                .hasMessageContaining(id.value().toString())
                .hasNoCause();
    }

    /** A strategy with no flows yet, written past the aggregate so a test can list a bad one. */
    private ScenarioId scenarioStoredDirectly(String currency) {
        return scenarioStoredDirectly("Stored directly", currency);
    }

    private ScenarioId scenarioStoredDirectly(String name, String currency) {
        ScenarioId id = ScenarioId.generate();
        jdbc().sql("INSERT INTO planning.scenario VALUES (:id, :name, :currency, 0)")
                .param("id", id.value())
                .param("name", name)
                .param("currency", currency)
                .update();
        return id;
    }

    private void listDirectly(ScenarioId scenario, PlannedFlow flow, String currency) {
        jdbc().sql("INSERT INTO planning.scenario_flow (scenario_id, ordinal, flow_id, currency)"
                        + " VALUES (:scenario, 0, :flow, :currency)")
                .param("scenario", scenario.value())
                .param("flow", flow.id().value())
                .param("currency", currency)
                .update();
    }

    private PlannedFlow stored(String description, String amount) {
        PlannedFlow flow = PlannedFlow.plan(description, ils(amount), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        flows.save(flow);
        return flow;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
