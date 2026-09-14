package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What a client supplies to open an account: a name, a type and a currency. It has no id, because
 * identity is generated inside the service (ADR-021).
 */
public record OpenAccountRequest(
        @NotBlank String name,
        @NotNull AccountType type,
        @NotNull Currency currency) {

    OpenAccountCommand toCommand() {
        return new OpenAccountCommand(name, type, currency);
    }
}
