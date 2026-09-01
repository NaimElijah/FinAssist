package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One entry of one transaction, flattened and carrying the type of the account it was posted to.
 *
 * <p>A read model, deliberately general. The first caller is the translation that gives budgeting its
 * spend, and it would have been shorter to return that shape directly — but a query named for one
 * consumer becomes a second consumer's problem the moment there is one, and accounting is upstream of
 * everything. Whoever reads this filters it themselves.
 *
 * <p>The type travels with the line because the sign of an amount means nothing without it, and the
 * caller cannot look it up: an {@code AccountId} is all an entry carries.
 */
public record JournalLine(
        TransactionId transaction,
        LocalDate on,
        String description,
        AccountId account,
        AccountType accountType,
        Money amount) {

    public JournalLine {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(accountType, "accountType");
        Objects.requireNonNull(amount, "amount");
    }
}
