package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What the owner is projected to hold on one date: cash, invested, and the two together.
 *
 * <p>Cash may be negative — that is a projected overdraft, and reporting it is the most useful thing
 * a forecast does. Invested may not: nothing in this model divests, growth cannot fall below minus
 * one, so a negative invested balance means a caller has handed in something meaningless rather than
 * described a real position.
 *
 * <p>Both amounts are in one currency, which is what makes {@link #total()} a legitimate figure.
 */
public record BalancePoint(LocalDate date, Money cash, Money invested) {

    public BalancePoint {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(invested, "invested");

        if (cash.currency() != invested.currency()) {
            throw new IllegalArgumentException("Cash is in " + cash.currency() + " but investments are in "
                    + invested.currency() + "; FinAssist performs no conversion");
        }
        if (invested.isNegative()) {
            throw new IllegalArgumentException("An invested balance cannot be negative but was " + invested);
        }
    }

    public Currency currency() {
        return cash.currency();
    }

    /** Cash plus investments. Legitimate precisely because both are in the same currency. */
    public Money total() {
        return cash.plus(invested);
    }
}
