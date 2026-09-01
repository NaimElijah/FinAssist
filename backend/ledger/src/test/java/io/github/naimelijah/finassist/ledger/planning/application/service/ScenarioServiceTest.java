package io.github.naimelijah.finassist.ledger.planning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.planning.adapter.out.event.InMemoryPlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence.InMemoryPlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence.InMemoryScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
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

    private final InMemoryPlannedFlowRepository flows = new InMemoryPlannedFlowRepository();
    private final InMemoryScenarioRepository scenarios = new InMemoryScenarioRepository();
    private final InMemoryPlanningEvents events = new InMemoryPlanningEvents();
    private final PlanService planService = new PlanService(flows, events);
    private final ScenarioService service = new ScenarioService(scenarios, flows, events);

    @Test
    void Given_recorded_flows_When_a_scenario_is_defined_Then_it_is_stored_over_them() {
        PlannedFlow car = plan("New car", "40000.00", Currency.ILS);

        Scenario defined = define("Buy the car", Currency.ILS, List.of(car.id()));

        assertThat(scenarios.findById(defined.id())).contains(defined);
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
    }

    @Test
    void Given_the_same_flow_twice_When_a_scenario_is_defined_Then_the_domain_refuses_it() {
        PlannedFlow car = plan("New car", "40000.00", Currency.ILS);

        assertThatThrownBy(() -> define("Buy two cars", Currency.ILS, List.of(car.id(), car.id())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void Given_a_flow_in_another_currency_When_a_scenario_is_defined_Then_it_is_refused() {
        PlannedFlow dollars = plan("US tuition", "20000.00", Currency.USD);

        assertThatThrownBy(() -> define("Study abroad", Currency.ILS, List.of(dollars.id())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Given_a_scenario_is_defined_When_it_succeeds_Then_the_event_names_its_flows() {
        PlannedFlow car = plan("New car", "40000.00", Currency.ILS);

        Scenario defined = define("Buy the car", Currency.ILS, List.of(car.id()));

        assertThat(events.published())
                .contains(new PlanningEvent.ScenarioDefined(
                        defined.id(), "Buy the car", Currency.ILS, List.of(car.id())));
    }

    private Scenario define(String name, Currency currency, List<PlanId> ids) {
        return service.define(new DefineScenarioCommand(name, currency, ids, GrowthAssumption.none()));
    }

    private PlannedFlow plan(String description, String amount, Currency currency) {
        return planService.plan(new PlanFlowCommand(
                description, Money.of(amount, currency), Direction.OUTFLOW, FEB_1, new Recurrence.Once()));
    }
}
