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
     * @throws IllegalArgumentException from the domain if {@code realisedOn} is before the date the
     *     plan was committed
     */
    PlannedFlow realise(PlanId plan, LocalDate realisedOn);

    /**
     * @throws PlanNotFoundException if no such plan exists
     * @throws io.github.naimelijah.finassist.ledger.planning.domain.plan.IllegalPlanTransitionException
     *     if the plan is already {@code Realised} or {@code Cancelled}
     * @throws IllegalArgumentException from the domain if {@code reason} is blank, or the plan is
     *     {@code Committed} and {@code cancelledOn} is before the date it was committed
     */
    PlannedFlow cancel(PlanId plan, LocalDate cancelledOn, String reason);
}
