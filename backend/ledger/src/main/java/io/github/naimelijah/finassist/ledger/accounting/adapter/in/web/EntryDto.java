package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** One side of a transaction: the account it posts to and a signed amount, positive for a debit. */
public record EntryDto(
        @NotNull UUID account, @NotNull @Valid MoneyDto amount) {

    static EntryDto from(Entry entry) {
        return new EntryDto(entry.account().value(), MoneyDto.from(entry.amount()));
    }

    /**
     * @throws IllegalArgumentException if the amount is zero, or carries more precision than its currency
     *     admits
     */
    Entry toEntry() {
        return new Entry(new AccountId(account), amount.toMoney());
    }
}
