package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance;
import java.util.UUID;

/** What one account holds: the account's id and its balance, summed from its entries on every request. */
public record BalanceResponse(UUID account, MoneyDto balance) {

    static BalanceResponse from(Balance balance) {
        return new BalanceResponse(balance.account().value(), MoneyDto.from(balance.amount()));
    }
}
