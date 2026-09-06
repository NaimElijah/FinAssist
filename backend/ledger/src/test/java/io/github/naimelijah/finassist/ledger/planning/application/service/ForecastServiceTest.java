package io.github.naimelijah.finassist.ledger.planning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.CompareScenariosCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ScenarioNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPosition;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPositionProvider;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of projecting a strategy and comparing strategies.
 *
 * <p>Two things are pinned here that no domain test could reach. The opening position arrives through
 * a port that planning owns and that names no accounting type, which is what ADR-015 said the
 * application layer would have to supply. And the flows are re-read before every projection, so a plan
 * cancelled after the scenario was named stops being forecast — without that, the whole of
 * {@code countsTowardsProjection} would be unreachable in practice.
 */
class ForecastServiceTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final Horizon QUARTER = new Horizon(JAN_1, APRIL_1);
    private static final AccountRef CURRENT = AccountRef.of("current-account");

    private final PlannedFlowRepository flows = mock(PlannedFlowRepository.class);
    private final ScenarioRepository scenarios = mock(ScenarioRepository.class);
    private final StubOpeningPositions openings = new StubOpeningPositions();
    private final ForecastService service = new ForecastService(scenarios, flows, openings);

    @Test
    void Given_a_scenario_and_an_opening_position_When_projected_Then_the_flow_is_taken_off_the_cash() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario scenario = scenarioOverOneOutflow("Buy the car", "1000.00");

        Projection projection = service.project(project(scenario.id()));

        assertThat(projection.endState().cash()).isEqualTo(ils("4000.00"));
    }

    @Test
    void Given_no_accounts_named_as_cash_When_projected_Then_the_opening_position_is_asked_for_anyway() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario scenario = scenarioOverOneOutflow("Buy the car", "1000.00");

        service.project(new ProjectScenarioCommand(scenario.id(), QUARTER, Cadence.MONTHLY, List.of(), List.of()));

        assertThat(openings.askedCash).isEmpty();
        assertThat(openings.askedCurrency).isEqualTo(Currency.ILS);
    }

    /**
     * The reason a projection re-reads its flows. A {@code Scenario} holds them by value, so the copy
     * stored when it was named keeps whatever state they had then — and a cancelled plan would go on
     * being forecast for ever, with {@code countsTowardsProjection} never reached.
     */
    @Test
    void Given_a_flow_cancelled_after_the_scenario_was_named_When_projected_Then_it_no_longer_counts() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario scenario = scenarioOverOneOutflow("Buy the car", "1000.00");

        stored(scenario.flows().getFirst().cancel(FEB_1, "too expensive"));

        assertThat(service.project(project(scenario.id())).endState().cash()).isEqualTo(ils("5000.00"));
    }

    @Test
    void Given_an_unknown_scenario_When_projected_Then_it_is_refused() {
        openings.holds(ils("5000.00"), ils("0.00"));

        assertThatThrownBy(() -> service.project(project(ScenarioId.generate())))
                .isInstanceOf(ScenarioNotFoundException.class);
    }

    /**
     * {@code Projection.of} would refuse the mismatch too, but it would name the projection rather than
     * the port that produced the wrong figure. The port promised the currency it was asked for.
     */
    @Test
    void Given_a_provider_answering_in_another_currency_When_projected_Then_it_is_refused() {
        openings.holds(Money.of("5000.00", Currency.USD), Money.of("0.00", Currency.USD));
        Scenario scenario = scenarioOverOneOutflow("Buy the car", "1000.00");

        assertThatThrownBy(() -> service.project(project(scenario.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpeningPositionProvider returned");
    }

    @Test
    void Given_a_horizon_that_is_not_a_whole_number_of_steps_When_projected_Then_it_is_refused() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario scenario = scenarioOverOneOutflow("Buy the car", "1000.00");

        assertThatThrownBy(() -> service.project(new ProjectScenarioCommand(
                        scenario.id(),
                        new Horizon(JAN_1, LocalDate.of(2026, 4, 15)),
                        Cadence.MONTHLY,
                        List.of(CURRENT),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void Given_a_strategy_and_a_baseline_When_compared_Then_the_divergence_is_reported() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario baseline = scenarioOver("Do nothing", List.of());
        Scenario buy = scenarioOverOneOutflow("Buy the car", "1000.00");

        Comparison comparison = service.compare(new CompareScenariosCommand(
                List.of(baseline.id(), buy.id()), QUARTER, Cadence.MONTHLY, List.of(CURRENT), List.of()));

        assertThat(comparison.names()).containsExactly("Do nothing", "Buy the car");
        assertThat(comparison.endStates().get("Buy the car").cash()).isEqualTo(ils("4000.00"));
        assertThat(comparison.divergenceFrom("Do nothing")).containsOnlyKeys("Buy the car");
    }

    @Test
    void Given_a_single_scenario_When_compared_Then_the_domain_refuses_it() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario buy = scenarioOverOneOutflow("Buy the car", "1000.00");

        assertThatThrownBy(() -> service.compare(new CompareScenariosCommand(
                        List.of(buy.id()), QUARTER, Cadence.MONTHLY, List.of(CURRENT), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void Given_an_unknown_scenario_among_several_When_compared_Then_it_is_refused() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario baseline = scenarioOver("Do nothing", List.of());

        assertThatThrownBy(() -> service.compare(new CompareScenariosCommand(
                        List.of(baseline.id(), ScenarioId.generate()),
                        QUARTER,
                        Cadence.MONTHLY,
                        List.of(CURRENT),
                        List.of())))
                .isInstanceOf(ScenarioNotFoundException.class);
    }

    private ProjectScenarioCommand project(ScenarioId scenario) {
        return new ProjectScenarioCommand(scenario, QUARTER, Cadence.MONTHLY, List.of(CURRENT), List.of());
    }

    private Scenario scenarioOverOneOutflow(String name, String amount) {
        PlannedFlow flow = stored(PlannedFlow.plan(name, ils(amount), Direction.OUTFLOW, FEB_1, new Recurrence.Once()));
        return scenarioOver(name, List.of(flow));
    }

    /** A scenario the store holds, over flows the store also holds. */
    private Scenario scenarioOver(String name, List<PlannedFlow> over) {
        Scenario scenario = Scenario.of(name, Currency.ILS, over, GrowthAssumption.none());
        when(scenarios.findById(scenario.id())).thenReturn(java.util.Optional.of(scenario));
        return scenario;
    }

    /**
     * A flow as the store currently holds it.
     *
     * <p>Re-stubbing an id already used is how a test says the plan moved on since the scenario was
     * named, which is precisely the situation the re-read exists to handle.
     */
    private PlannedFlow stored(PlannedFlow flow) {
        when(flows.findById(flow.id())).thenReturn(java.util.Optional.of(flow));
        return flow;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    /** Stands in for the translation over accounting, which lives outside both contexts (ADR-022). */
    private static final class StubOpeningPositions implements OpeningPositionProvider {

        private OpeningPosition position = OpeningPosition.zero(Currency.ILS);
        private List<AccountRef> askedCash = List.of();
        private Currency askedCurrency;
        private int calls;

        void holds(Money cash, Money invested) {
            position = new OpeningPosition(cash, invested);
        }

        @Override
        public OpeningPosition positionOf(List<AccountRef> cash, List<AccountRef> invested, Currency currency) {
            askedCash = cash;
            askedCurrency = currency;
            calls++;
            return position;
        }
    }

    /**
     * Keying the comparison by name means the second scenario replaces the first, so without this check
     * a comparison of two same-named strategies comes back describing one of them - and a caller
     * comparing exactly two is told, misleadingly, that it needs at least two.
     */
    @Test
    void Given_two_scenarios_sharing_a_name_When_compared_Then_neither_is_silently_dropped() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario first = scenarioOverOneOutflow("Buy the car", "1000.00");
        Scenario second = scenarioOverOneOutflow("Buy the car", "2000.00");

        assertThatThrownBy(() -> service.compare(new CompareScenariosCommand(
                        List.of(first.id(), second.id()), QUARTER, Cadence.MONTHLY, List.of(CURRENT), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both named");
    }

    /** Identical arguments for every scenario of one currency, so the port is asked once. */
    @Test
    void Given_several_scenarios_in_one_currency_When_compared_Then_the_opening_position_is_read_once() {
        openings.holds(ils("5000.00"), ils("0.00"));
        Scenario baseline = scenarioOver("Do nothing", List.of());
        Scenario buy = scenarioOverOneOutflow("Buy the car", "1000.00");

        service.compare(new CompareScenariosCommand(
                List.of(baseline.id(), buy.id()), QUARTER, Cadence.MONTHLY, List.of(CURRENT), List.of()));

        assertThat(openings.calls).isEqualTo(1);
    }

    /**
     * Repositories have no delete, so a scenario naming a flow that does not resolve cannot arise
     * through the in-port. Planted directly in the store to prove the guard exists: skipping the flow
     * instead would quietly forecast a strategy missing one of its own commitments.
     */
    @Test
    void Given_a_scenario_naming_a_flow_that_was_never_stored_When_projected_Then_it_is_refused() {
        openings.holds(ils("5000.00"), ils("0.00"));
        PlannedFlow unsaved =
                PlannedFlow.plan("Ghost", ils("1000.00"), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        Scenario dangling = Scenario.of("Dangling", Currency.ILS, List.of(unsaved), GrowthAssumption.none());
        when(scenarios.findById(dangling.id())).thenReturn(java.util.Optional.of(dangling));

        assertThatThrownBy(() -> service.project(project(dangling.id()))).isInstanceOf(PlanNotFoundException.class);
    }
}
