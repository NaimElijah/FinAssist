package io.github.naimelijah.finassist.ledger.planning.application.service;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import java.util.List;
import java.util.Objects;

/**
 * Names strategies.
 *
 * <p>Resolves each flow by identity, so a scenario cannot be defined over an intention that was never
 * recorded. {@code Scenario} then refuses one listed twice, or one in another currency.
 */
public class ScenarioService implements DefineScenarioUseCase {

    private final ScenarioRepository scenarios;
    private final PlannedFlowRepository flows;
    private final PlanningEvents events;

    public ScenarioService(ScenarioRepository scenarios, PlannedFlowRepository flows, PlanningEvents events) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.flows = Objects.requireNonNull(flows, "flows");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public Scenario define(DefineScenarioCommand command) {
        Objects.requireNonNull(command, "command");

        List<PlannedFlow> resolved = command.flows().stream()
                .map(id -> flows.findById(id).orElseThrow(() -> new PlanNotFoundException(id)))
                .toList();

        Scenario scenario = Scenario.of(command.name(), command.currency(), resolved, command.growth());
        scenarios.save(scenario);
        events.publish(new PlanningEvent.ScenarioDefined(
                scenario.id(),
                scenario.name(),
                scenario.currency(),
                scenario.flows().stream().map(PlannedFlow::id).toList()));
        return scenario;
    }
}
