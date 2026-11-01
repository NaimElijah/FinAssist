package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

/**
 * Thrown when a transaction is asked for a state change its current state does not allow.
 *
 * <p>Where the concrete state is known statically the transition simply does not exist as a method,
 * so the compiler refuses the call. This exception covers the other case: a {@link Transaction}
 * holds its state as the sealed interface, so the check happens at run time.
 */
public class IllegalTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param from the state the transaction is in
     * @param attempted the refused change as a verb, such as {@code "clear"}; the message reads
     *     "Cannot clear a transaction that is Pending"
     */
    public IllegalTransitionException(TransactionState from, String attempted) {
        super("Cannot " + attempted + " a transaction that is " + from.name());
    }
}
