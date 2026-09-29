package io.github.naimelijah.finassist.ledger.accounting.domain.account;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.Objects;

/**
 * An account in the chart of accounts: an identity, a name, a type and exactly one currency.
 *
 * <p><strong>There is no balance field, and there will not be one.</strong> A balance is
 * {@link io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance}, derived by summing
 * the entries that reference this account. A stored balance is a second source of truth that drifts
 * from the entries the first time anything goes wrong, and no amount of care prevents that — only
 * not having the field does.
 *
 * <p>One account, one currency (ADR-012). That an entry matches its account's currency is
 * <strong>not</strong> checked here and cannot be: an {@code Entry} holds an {@code AccountId}, not
 * an {@code Account}, so nothing in the domain can look the currency up. Enforcing it needs a
 * repository, so it belongs to the application layer: {@code TransactionService} refuses a
 * mis-currencied entry with {@code EntryCurrencyMismatchException} before anything is stored.
 */
public record Account(AccountId id, String name, AccountType type, Currency currency) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Account name must not be blank");
        }
        name = name.strip();
    }

    public static Account open(String name, AccountType type, Currency currency) {
        return new Account(AccountId.generate(), name, type, currency);
    }
}
