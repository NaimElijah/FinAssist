package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.Objects;

/**
 * One side of a transaction: an amount posted to an account.
 *
 * <p>The amount is signed. Positive is a debit, negative is a credit — whether that increases or
 * decreases the account depends on its
 * {@link io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType}.
 *
 * <p>An entry never exists alone. It lives inside a {@link Transaction}, which is the aggregate root
 * and the thing that guarantees entries balance. A zero amount is refused: it records nothing and
 * would let a transaction appear balanced while saying nothing at all.
 */
public record Entry(AccountId account, Money amount) {

    public Entry {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("An entry of zero records nothing; omit it instead");
        }
    }

    public boolean isDebit() {
        return amount.isPositive();
    }

    public boolean isCredit() {
        return amount.isNegative();
    }
}
