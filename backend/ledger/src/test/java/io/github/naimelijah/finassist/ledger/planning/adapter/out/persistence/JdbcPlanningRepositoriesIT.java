package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
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

    @Test
    void Given_a_scenario_over_flows_When_it_is_read_back_Then_its_flows_come_back_in_order() {
        PlannedFlow car = stored("New car", "40000.00");
        PlannedFlow tuition = stored("Tuition", "20000.00");
        Scenario scenario = Scenario.of("Spend it all", Currency.ILS, List.of(car, tuition), GrowthAssumption.none());

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

    @Test
    void Given_a_flow_When_read_over_an_independent_connection_Then_it_is_still_there() {
        PlannedFlow car = stored("New car", "40000.00");

        PlannedFlowRepository elsewhere = new JdbcPlannedFlowRepository(freshConnection());

        assertThat(elsewhere.findById(car.id())).contains(car);
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
