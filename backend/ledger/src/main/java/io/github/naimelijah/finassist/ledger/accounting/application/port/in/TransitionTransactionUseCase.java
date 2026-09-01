package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import java.time.LocalDate;

/**
 * Moves a transaction through its lifecycle.
 *
 * <p>One interface for three transitions rather than three interfaces, because they share a subject,
 * a service and a caller. Each takes its date as an argument; none reads a clock.
 *
 * @see io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState
 */
public interface TransitionTransactionUseCase {

    /**
     * @throws TransactionNotFoundException if no such transaction exists
     * @throws io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException
     *     if the transaction is not {@code Pending}
     */
    Transaction clear(TransactionId transaction, LocalDate clearedOn);

    /**
     * @throws TransactionNotFoundException if no such transaction exists
     * @throws io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException
     *     if the transaction is not {@code Cleared}
     */
    Transaction reconcile(TransactionId transaction, LocalDate reconciledOn);

    /**
     * @throws TransactionNotFoundException if no such transaction exists
     * @throws io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException
     *     if the transaction is already {@code Reconciled} or {@code Voided}
     */
    Transaction markVoided(TransactionId transaction, LocalDate voidedOn, String reason);
}
