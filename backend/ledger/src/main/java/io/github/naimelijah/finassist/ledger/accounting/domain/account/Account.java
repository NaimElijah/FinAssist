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
 * repository, so it belongs to the application layer: {@code RecordTransactionUseCase} refuses a
 * mis-currencied entry with {@code EntryCurrencyMismatchException} before anything is stored, and
 * {@code V1_6}'s foreign key {@code journal_entry_in_account_currency} refuses one again as it is
 * inserted.
 */
public record Account(AccountId id, String name, AccountType type, Currency currency) {

    /**
     * Creates an account, stripping surrounding whitespace from {@code name}.
     *
     * @throws NullPointerException if any component is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
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

    /**
     * Opens a new account under a freshly generated identity.
     *
     * <p>Name uniqueness is not checked here: it is a property of the whole chart, enforced by
     * {@code AccountService} and by the store.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static Account open(String name, AccountType type, Currency currency) {
        return new Account(AccountId.generate(), name, type, currency);
    }
}
