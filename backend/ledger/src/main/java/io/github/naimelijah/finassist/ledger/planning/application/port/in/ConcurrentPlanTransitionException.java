package io.github.naimelijah.finassist.ledger.planning.application.port.in;

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

    /**
     * The message becomes the problem's {@code detail}, which the web client shows the owner as it stands,
     * so it carries no identifier: the problem's {@code instance} already says which flow this was.
     */
    public ConcurrentPlanTransitionException() {
        super("This planned flow changed while your change was being saved, so your change was not stored."
                + " Check its current state and decide again.");
    }
}
