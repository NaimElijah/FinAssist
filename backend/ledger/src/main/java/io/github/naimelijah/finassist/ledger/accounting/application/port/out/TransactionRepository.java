package io.github.naimelijah.finassist.ledger.accounting.application.port.out;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The journal.
 *
 * <p><strong>There is no delete, and entries are never edited.</strong> A lifecycle change is stored by
 * saving the aggregate in its new state, which is what {@link Transaction} produces — every transition
 * returns a new value rather than mutating one. Voiding keeps the entries and stops them counting. That
 * is ADR-020's append-only requirement expressed as a port shape rather than deferred to a schema that
 * does not exist yet. See ADR-021.
 */
public interface TransactionRepository {

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
     * Stores the transaction, replacing any earlier state of the same identity.
     *
     * <p>{@code recordedBy} is written once, on the row this identity is first recorded with; every
     * later call — a lifecycle transition — writes it onto the new {@code journal_transaction_state}
     * row it appends, which may name a different actor from the one who first recorded the
     * transaction (ADR-033).
     */
    void save(Transaction transaction, Actor recordedBy);
}
