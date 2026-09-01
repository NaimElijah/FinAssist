package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;

/** Records what the owner intends to do with money. */
public interface PlanFlowUseCase {

    /**
     * @return the stored flow, {@code Planned} and carrying its generated identity
     * @throws IllegalArgumentException from the domain when the amount is not positive, the
     *     description says nothing, or a recurrence would end before it begins
     */
    PlannedFlow plan(PlanFlowCommand command);
}
