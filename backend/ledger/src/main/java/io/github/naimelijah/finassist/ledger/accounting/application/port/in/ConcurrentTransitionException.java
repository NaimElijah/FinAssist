package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

/**
 * The transaction moved on between the read a lifecycle change was validated against and the write
 * that would have stored it, so the change was not stored.
 *
 * <p>It is not an {@code IllegalTransitionException}, whose message says the current state forbids the
 * move: a void validated against {@code Pending} is refused here even when the transaction has since
 * been {@code Cleared}, a state that would allow it. Whoever asked decided on a state that is gone,
 * and only they can say whether the change still stands. See ADR-075.
 */
public class ConcurrentTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The message becomes the problem's {@code detail}, which the web client shows the owner as it stands,
     * on a page that names a row by its description and date, so it carries no identifier: the
     * problem's {@code instance} already says which transaction this was.
     */
    public ConcurrentTransitionException() {
        super("This transaction changed while your change was being saved, so your change was not stored."
                + " Check its current state and decide again.");
    }
}
