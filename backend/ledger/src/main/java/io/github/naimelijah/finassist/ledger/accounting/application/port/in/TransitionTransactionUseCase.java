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
 * <p>Each also takes the {@link Actor} making the change — who cleared, reconciled or voided the
 * transaction may differ from who first recorded it, and each new {@code
 * journal_transaction_state} row names its own writer (ADR-033).
 *
 * @see io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState
 */
public interface TransitionTransactionUseCase {

    /**
     * Confirms a pending transaction on {@code clearedOn} and stores the new state.
     *
     * @param recordedBy who is clearing it, written onto the new state row
     * @return the cleared transaction
     * @throws TransactionNotFoundException if no such transaction exists
     * @throws io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException
     *     if the transaction is not {@code Pending}
     * @throws IllegalArgumentException if {@code clearedOn} is before the day the transaction
     *     occurred
     */
    Transaction clear(TransactionId transaction, LocalDate clearedOn, Actor recordedBy);

    /**
     * Matches a cleared transaction to a statement on {@code reconciledOn} and stores the new
     * state. A reconciled transaction is frozen.
     *
     * @param recordedBy who is reconciling it, written onto the new state row
     * @return the reconciled transaction
     * @throws TransactionNotFoundException if no such transaction exists
     * @throws io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException
     *     if the transaction is not {@code Cleared}
     * @throws IllegalArgumentException if {@code reconciledOn} is before the day it cleared
     */
    Transaction reconcile(TransactionId transaction, LocalDate reconciledOn, Actor recordedBy);

    /**
     * Voids a pending or cleared transaction on {@code voidedOn} and stores the new state. Its
     * entries remain in the journal and stop counting towards a balance.
     *
     * @param reason why it is voided; must not be blank
     * @param recordedBy who is voiding it, written onto the new state row
     * @return the voided transaction
     * @throws TransactionNotFoundException if no such transaction exists
     * @throws io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException
     *     if the transaction is already {@code Reconciled} or {@code Voided}
     * @throws IllegalArgumentException if {@code reason} is blank, or {@code voidedOn} is before
     *     the day the transaction occurred or, once cleared, before the day it cleared
     */
    Transaction markVoided(TransactionId transaction, LocalDate voidedOn, String reason, Actor recordedBy);
}
