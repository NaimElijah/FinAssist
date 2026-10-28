package io.github.naimelijah.finassist.ledger.planning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.planning.adapter.out.event.InMemoryPlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;
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

/** The contract of naming a strategy. */
class ScenarioServiceTest {

    private static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);

    private final PlannedFlowRepository flows = mock(PlannedFlowRepository.class);
    private final ScenarioRepository scenarios = mock(ScenarioRepository.class);
    private final InMemoryPlanningEvents events = new InMemoryPlanningEvents();
    private final ScenarioService service = new ScenarioService(scenarios, flows, events);

    @Test
    void Given_recorded_flows_When_a_scenario_is_defined_Then_it_is_stored_over_them() {
        PlannedFlow car = plan("New car", "40000.00", Currency.ILS);

        Scenario defined = define("Buy the car", Currency.ILS, List.of(car.id()));

        verify(scenarios).save(defined);
        assertThat(defined.flows()).containsExactly(car);
    }

    /** The do-nothing baseline. A comparison needs one, so an empty flow list has to be legal. */
    @Test
    void Given_no_flows_at_all_When_a_scenario_is_defined_Then_it_is_the_baseline() {
        Scenario baseline = define("Do nothing", Currency.ILS, List.of());

        assertThat(baseline.flows()).isEmpty();
        assertThat(baseline.forecastableFlows()).isEmpty();
    }

    @Test
    void Given_a_flow_that_was_never_recorded_When_a_scenario_is_defined_Then_it_is_refused() {
        assertThatThrownBy(() -> define("Buy the car", Currency.ILS, List.of(PlanId.generate())))
                .isInstanceOf(PlanNotFoundException.class);

        assertNothingStoredOrPublished();
    }

    @Test
    void Given_the_same_flow_twice_When_a_scenario_is_defined_Then_the_domain_refuses_it() {
        PlannedFlow car = plan("New car", "40000.00", Currency.ILS);

        assertThatThrownBy(() -> define("Buy two cars", Currency.ILS, List.of(car.id(), car.id())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");

        assertNothingStoredOrPublished();
    }

    @Test
    void Given_a_flow_in_another_currency_When_a_scenario_is_defined_Then_it_is_refused() {
        PlannedFlow dollars = plan("US tuition", "20000.00", Currency.USD);

        assertThatThrownBy(() -> define("Study abroad", Currency.ILS, List.of(dollars.id())))
                .isInstanceOf(IllegalArgumentException.class);

        assertNothingStoredOrPublished();
    }

    @Test
    void Given_a_scenario_is_defined_When_it_succeeds_Then_the_event_names_its_flows() {
        PlannedFlow car = plan("New car", "40000.00", Currency.ILS);

        Scenario defined = define("Buy the car", Currency.ILS, List.of(car.id()));

        assertThat(events.published())
                .contains(new PlanningEvent.ScenarioDefined(
                        defined.id(), "Buy the car", Currency.ILS, List.of(car.id())));
    }

    /** Listing derives nothing of its own - ordering is the repository's job. */
    @Test
    void Given_scenarios_stored_When_listed_Then_they_come_back_in_the_repositorys_order() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());
        Scenario aggressive = Scenario.baseline("Aggressive", Currency.ILS, GrowthAssumption.none());
        when(scenarios.all()).thenReturn(List.of(aggressive, baseline));

        assertThat(service.all()).containsExactly(aggressive, baseline);
    }

    /** A refused command must leave no trace, or the event log stops describing what was planned. */
    private void assertNothingStoredOrPublished() {
        verify(scenarios, never()).save(any());
        assertThat(events.published()).isEmpty();
    }

    private Scenario define(String name, Currency currency, List<PlanId> ids) {
        return service.define(new DefineScenarioCommand(name, currency, ids, GrowthAssumption.none()));
    }

    /** A flow the store already holds, so the service can resolve the id a command names. */
    private PlannedFlow plan(String description, String amount, Currency currency) {
        PlannedFlow flow = PlannedFlow.plan(
                description, Money.of(amount, currency), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        when(flows.findById(flow.id())).thenReturn(java.util.Optional.of(flow));
        return flow;
    }
}
