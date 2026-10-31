package io.github.naimelijah.finassist.ledger.planning.domain.plan;

/**
 * Thrown when a planned flow is asked for a state change its current state does not allow.
 *
 * <p>Where the concrete state is known statically the transition simply does not exist as a method,
 * so the compiler refuses the call. This exception covers the other case: a {@link PlannedFlow}
 * holds its state as the sealed interface, so the check happens at run time.
 */
public class IllegalPlanTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IllegalPlanTransitionException(PlanState from, String attempted) {
        super("Cannot " + attempted + " a plan that is " + from.name());
    }
}
