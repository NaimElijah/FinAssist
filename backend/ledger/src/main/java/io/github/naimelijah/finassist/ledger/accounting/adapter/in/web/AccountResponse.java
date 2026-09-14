package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.UUID;

/**
 * An account in the chart of accounts: its identity, name, type and one currency. It carries no
 * balance, because a balance is derived on request, never stored.
 */
public record AccountResponse(UUID id, String name, AccountType type, Currency currency) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.id().value(), account.name(), account.type(), account.currency());
    }
}
