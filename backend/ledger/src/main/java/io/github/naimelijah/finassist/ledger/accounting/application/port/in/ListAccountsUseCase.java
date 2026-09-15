package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import java.util.List;

/** Reads the chart of accounts. */
public interface ListAccountsUseCase {

    /**
     * Every account, ordered by name. Nothing is derived here — a balance is {@link
     * ViewBalanceUseCase#balanceOf}'s job, not this one's.
     */
    List<Account> all();
}
