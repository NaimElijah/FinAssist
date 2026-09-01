package io.github.naimelijah.finassist.ledger.planning.application.service;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.TransitionPlanUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Records intentions and moves them through their lifecycle.
 *
 * <p>Holds no rule of its own. Whether a transition is legal is {@code PlanState}'s business, and it
 * answers by declaring no method — a {@code Realised} plan cannot be cancelled because there is
 * nothing to call, and the run-time guard exists because this service holds the state as the sealed
 * interface rather than a concrete type.
 */
public class PlanService implements PlanFlowUseCase, TransitionPlanUseCase {

    private final PlannedFlowRepository flows;
    private final PlanningEvents events;

    public PlanService(PlannedFlowRepository flows, PlanningEvents events) {
        this.flows = Objects.requireNonNull(flows, "flows");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public PlannedFlow plan(PlanFlowCommand command) {
        Objects.requireNonNull(command, "command");
        PlannedFlow planned = PlannedFlow.plan(
                command.description(), command.amount(), command.direction(), command.startsOn(), command.recurrence());
        flows.save(planned);
        events.publish(new PlanningEvent.FlowPlanned(
                planned.id(), planned.description(), planned.amount(), planned.direction(), planned.startsOn()));
        return planned;
    }

    @Override
    public PlannedFlow commit(PlanId plan, LocalDate committedOn) {
        PlannedFlow committed = require(plan).commit(committedOn);
        flows.save(committed);
        events.publish(new PlanningEvent.PlanCommitted(committed.id(), committedOn));
        return committed;
    }

    @Override
    public PlannedFlow realise(PlanId plan, LocalDate realisedOn) {
        PlannedFlow realised = require(plan).realise(realisedOn);
        flows.save(realised);
        events.publish(new PlanningEvent.PlanRealised(realised.id(), realisedOn));
        return realised;
    }

    @Override
    public PlannedFlow cancel(PlanId plan, LocalDate cancelledOn, String reason) {
        PlannedFlow cancelled = require(plan).cancel(cancelledOn, reason);
        flows.save(cancelled);
        // Read back off the aggregate, so the event carries the stripped form that was stored.
        events.publish(new PlanningEvent.PlanCancelled(cancelled.id(), cancelledOn, reason.strip()));
        return cancelled;
    }

    private PlannedFlow require(PlanId plan) {
        Objects.requireNonNull(plan, "plan");
        return flows.findById(plan).orElseThrow(() -> new PlanNotFoundException(plan));
    }
}
