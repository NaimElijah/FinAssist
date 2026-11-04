package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;

/**
 * The planned flow moved on between the read a lifecycle change was validated against and the write
 * that would have stored it, so the change was not stored.
 *
 * <p>It is not an {@code IllegalPlanTransitionException}, whose message says the current state forbids
 * the move: a cancel validated against {@code Planned} is refused here even when the flow has since
 * been {@code Committed}, a state that would allow it. Whoever asked decided on a state that is gone,
 * and only they can say whether the change still stands. See ADR-075.
 */
public class ConcurrentPlanTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentPlanTransitionException(PlanId plan) {
        super("Planned flow " + plan + " changed while this change was being made, so it was not stored;"
                + " read the flow again and decide");
    }
}
