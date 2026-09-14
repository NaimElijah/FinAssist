package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * An account in the chart of accounts: its identity, name, type and one currency. It carries no
 * balance, because a balance is derived on request, never stored. Every member is always present, and
 * marked required so a client generated from the contract does not read it as optional.
 */
public record AccountResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currency currency) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.id().value(), account.name(), account.type(), account.currency());
    }
}
