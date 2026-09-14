package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads the journal as whole transactions, lifecycle and all.
 *
 * <p><strong>Voided transactions are included.</strong> That is the opposite of {@link
 * ViewJournalUseCase#postedBetween}, which excludes them because it answers what counts towards a
 * balance. This port answers what happened, including what was later undone — a lifecycle view
 * that dropped a voided transaction would make voiding look like deletion, which is exactly what
 * ADR-020 exists to prevent.
 *
 * <p>Both methods return their results ordered by occurred date then identity — {@code
 * (occurredOn, id)} — which {@code TransactionRepository} guarantees. A list whose order depends
 * on a query planner is a defect in a UI that renders it.
 */
public interface ViewTransactionsUseCase {

    /**
     * Every transaction that occurred in the half-open range, voided ones included, ordered by
     * occurred date then identity.
     *
     * @param fromInclusive the first date in range
     * @param toExclusive the first date past it
     */
    List<Transaction> between(LocalDate fromInclusive, LocalDate toExclusive);

    /**
     * Every transaction holding an entry against the account, voided ones included, ordered by
     * occurred date then identity.
     *
     * @throws AccountNotFoundException if no such account exists, checked the same way {@link
     *     ViewBalanceUseCase#balanceOf} checks it — so an unknown account is distinguishable from
     *     an account with no transactions at all
     */
    List<Transaction> forAccount(AccountId account);
}
