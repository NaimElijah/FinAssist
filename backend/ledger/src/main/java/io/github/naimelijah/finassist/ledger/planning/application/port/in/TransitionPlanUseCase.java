package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import java.time.LocalDate;

/**
 * Moves a planned flow through its lifecycle.
 *
 * <p>The mirror of {@code TransitionTransactionUseCase}, one interface for three transitions. Each
 * takes its date as an argument; none reads a clock.
 *
 * @see io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState
 */
public interface TransitionPlanUseCase {

    /**
     * @throws PlanNotFoundException if no such plan exists
     * @throws io.github.naimelijah.finassist.ledger.planning.domain.plan.IllegalPlanTransitionException
     *     if the plan is not {@code Planned}
     */
    PlannedFlow commit(PlanId plan, LocalDate committedOn);

    /**
     * Marks the money as having moved. The flow stops counting towards a projection from here on.
     *
     * @throws PlanNotFoundException if no such plan exists
     * @throws io.github.naimelijah.finassist.ledger.planning.domain.plan.IllegalPlanTransitionException
     *     if the plan is not {@code Committed}
     */
    PlannedFlow realise(PlanId plan, LocalDate realisedOn);

    /**
     * @throws PlanNotFoundException if no such plan exists
     * @throws io.github.naimelijah.finassist.ledger.planning.domain.plan.IllegalPlanTransitionException
     *     if the plan is already {@code Realised} or {@code Cancelled}
     */
    PlannedFlow cancel(PlanId plan, LocalDate cancelledOn, String reason);
}
