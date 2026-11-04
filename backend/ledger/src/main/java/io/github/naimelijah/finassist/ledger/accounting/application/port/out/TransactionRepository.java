package io.github.naimelijah.finassist.ledger.accounting.application.port.out;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The journal.
 *
 * <p><strong>There is no delete, and entries are never edited.</strong> A lifecycle change is stored by
 * saving the aggregate in its new state, with {@link #saveTransition}: that state is what {@link
 * Transaction} produces — every transition returns a new value rather than mutating one. Voiding keeps
 * the entries and stops them counting. That
 * is ADR-020's append-only requirement expressed as a port shape (ADR-021), and the schema enforces it
 * as well: {@code V1_1__accounting_schema.sql} puts a trigger on each journal table that refuses an
 * {@code UPDATE} or a {@code DELETE} (ADR-024). {@code V1_3} to {@code V1_5} go further: a recorded
 * transaction's entries are closed, its states follow one another along the lifecycle {@link
 * Transaction} allows, and a header is committed only with its entries and its opening state.
 */
public interface TransactionRepository {

    /** The transaction of that identity, in its current state, or empty when none is stored. */
    Optional<Transaction> findById(TransactionId id);

    /**
     * Every transaction holding an entry against {@code account}, voided ones included, ordered by
     * occurred date then identity ({@code occurredOn, id}) — the same order {@link #findBetween}
     * promises.
     */
    List<Transaction> findByAccount(AccountId account);

    /**
     * Every transaction that occurred in the half-open range, voided ones included, ordered by
     * occurred date then identity ({@code occurredOn, id}).
     *
     * <p>The index {@code journal_transaction_occurred_on_idx (occurred_on, id)} is what this order
     * follows, and the durable adapter keeps it with an explicit {@code ORDER BY} rather than
     * trusting the index to be read in order — a list whose order depends on a query planner is a
     * defect for a caller folding over it, such as a projection or a budget review.
     *
     * @param fromInclusive the first date in range
     * @param toExclusive the first date past it, matching {@code BudgetPeriod} and {@code Horizon}
     */
    List<Transaction> findBetween(LocalDate fromInclusive, LocalDate toExclusive);

    /**
     * Records a transaction with its entries, in its opening state. Saving an identity already
     * recorded, exactly as it is stored, does nothing; a lifecycle change goes through {@link
     * #saveTransition}. Nothing stored is overwritten.
     *
     * <p>Precondition: the first save of an identity is {@code Pending}. The durable store refuses
     * one that is not ({@code V1_4__journal_state_transitions.sql}).
     *
     * <p>{@code recordedBy} is written on the rows this identity is first recorded with, and never
     * changes afterwards (ADR-033).
     *
     * @throws IllegalStateException if the identity is already recorded with other entries, or in
     *     another state
     */
    void save(Transaction transaction, Actor recordedBy);

    /**
     * Stores one lifecycle move: appends the state of {@code next} after the state stored, provided
     * the stored state is still the one the move was decided on. Nothing stored is overwritten.
     *
     * <p>The caller passes the state it validated against, the one it read before asking {@link
     * Transaction} for the move, not whatever it believes is current. That is the whole check: a move
     * is decided on one read and written later, and without it two moves decided on the same read could
     * both land (ADR-075).
     *
     * <p>Precondition: {@code next} is one move {@link Transaction} allows from {@code
     * validatedAgainst} — never two moves at once, and never none. Save each transition as it happens.
     * The durable store refuses a call that breaks this ({@code V1_4__journal_state_transitions.sql}),
     * as a fault in the caller and not as one of the refusals below.
     *
     * <p>{@code recordedBy} is written onto the new {@code journal_transaction_state} row, which may
     * name a different actor from the one who first recorded the transaction (ADR-033).
     *
     * @param validatedAgainst the state the move was decided on
     * @param next the transaction in its new state, with the entries it was recorded with
     * @param recordedBy who is making the move
     * @throws io.github.naimelijah.finassist.ledger.accounting.application.port.in.ConcurrentTransitionException
     *     if the stored state is no longer {@code validatedAgainst}, or another move lands first; nothing
     *     was stored
     * @throws IllegalStateException if nothing is stored for the identity, which is a corrupt store or a
     *     misuse rather than a race since the journal never deletes, or if {@code next} carries other
     *     entries than those recorded
     */
    void saveTransition(TransactionState validatedAgainst, Transaction next, Actor recordedBy);
}
