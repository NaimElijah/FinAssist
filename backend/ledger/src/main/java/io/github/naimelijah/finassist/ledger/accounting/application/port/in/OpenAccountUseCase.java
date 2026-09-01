package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;

/** Adds an account to the chart of accounts. */
public interface OpenAccountUseCase {

    /**
     * Opens an account and stores it.
     *
     * @return the opened account, carrying the identity the service generated
     * @throws DuplicateAccountNameException if an account of that name already exists — the rule the
     *     domain could not enforce, because uniqueness is a property of the set rather than the value
     */
    Account open(OpenAccountCommand command);
}
