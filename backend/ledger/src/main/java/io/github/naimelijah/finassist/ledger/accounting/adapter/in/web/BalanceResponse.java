package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * What one account holds: the account's id and its balance, summed from its entries on every request.
 * Both members are always present, and marked required in the contract.
 */
public record BalanceResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID account,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto balance) {

    static BalanceResponse from(Balance balance) {
        return new BalanceResponse(balance.account().value(), MoneyDto.from(balance.amount()));
    }
}
