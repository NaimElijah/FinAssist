package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance;

/** Reads what an account holds. */
public interface ViewBalanceUseCase {

    /**
     * Sums the account's entries. Nothing is stored and nothing is cached: the answer is derived on
     * every call, which is why it cannot disagree with the journal.
     *
     * @throws AccountNotFoundException if no such account exists. The account is loaded for its
     *     currency, which is the answer when no transaction touches it at all
     */
    Balance balanceOf(AccountId account);
}
