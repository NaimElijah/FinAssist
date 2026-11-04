package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;

/**
 * The transaction moved on between the read a lifecycle change was validated against and the write
 * that would have stored it, so the change was not stored.
 *
 * <p>It is not an {@code IllegalTransitionException}, whose message says the current state forbids the
 * move: a void validated against {@code Pending} is refused here even when the transaction has since
 * {@code Cleared}, a state that would allow it. Whoever asked decided on a state that is gone, and only
 * they can say whether the change still stands. See ADR-075.
 */
public class ConcurrentTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentTransitionException(TransactionId transaction) {
        super("Transaction " + transaction + " changed while this change was being made, so it was not stored;"
                + " read the transaction again and decide");
    }
}
