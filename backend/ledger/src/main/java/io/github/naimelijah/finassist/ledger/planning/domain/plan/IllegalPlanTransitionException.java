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

    /**
     * Builds the refusal for a transition the state does not allow.
     *
     * @param from the state the plan is in
     * @param attempted the verb the caller tried, such as {@code "cancel"}; the message reads
     *     "Cannot cancel a plan that is Realised"
     */
    public IllegalPlanTransitionException(PlanState from, String attempted) {
        super("Cannot " + attempted + " a plan that is " + from.name());
    }
}
