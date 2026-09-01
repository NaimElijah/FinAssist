package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;

/** No account of that identity is in the chart of accounts. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(AccountId account) {
        super("No account with id " + account);
    }
}
