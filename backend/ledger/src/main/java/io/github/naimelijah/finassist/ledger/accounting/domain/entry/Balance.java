package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.Collection;
import java.util.Objects;

/**
 * What an account holds, <strong>derived</strong> from the entries that reference it.
 *
 * <p>There is no setter and no stored counterpart. {@link Balance} is computed on demand from
 * transactions, so it cannot disagree with them — the failure mode a balance column always
 * eventually produces is unavailable here because the column does not exist.
 *
 * <p>Voided transactions are excluded. Their entries remain as history, which is the point of
 * voiding rather than deleting, but they do not move money.
 */
public record Balance(AccountId account, Money amount) {

    public Balance {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
    }

    /**
     * Sums every entry for {@code account} across {@code transactions}, ignoring voided ones.
     *
     * @param currency the account's currency; also the answer when no transaction touches it
     */
    public static Balance of(AccountId account, Currency currency, Collection<Transaction> transactions) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(transactions, "transactions");

        Money total = Money.zero(currency);
        for (Transaction transaction : transactions) {
            if (transaction.countsTowardsBalance()) {
                total = total.plus(transaction.netFor(account));
            }
        }
        return new Balance(account, total);
    }
}
