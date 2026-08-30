package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * How far one scenario has drifted from another on a given date.
 *
 * <p>Signed, and read as "this scenario minus the baseline": a positive {@link #cash()} means the
 * scenario leaves more cash on hand than the baseline does. Both amounts may be negative, which is
 * why this is not a {@link BalancePoint} — a difference is not a position, and an invested
 * difference of minus 500 is perfectly ordinary where an invested balance of minus 500 is not.
 */
public record Divergence(LocalDate date, Money cash, Money invested) {

    public Divergence {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(invested, "invested");
        if (cash.currency() != invested.currency()) {
            throw new IllegalArgumentException("Cash difference is in " + cash.currency()
                    + " but the investment difference is in " + invested.currency());
        }
    }

    /** The difference in total holdings. */
    public Money total() {
        return cash.plus(invested);
    }
}
