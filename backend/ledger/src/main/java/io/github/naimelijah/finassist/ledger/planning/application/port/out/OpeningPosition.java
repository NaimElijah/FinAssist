package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.Objects;

/**
 * What a projection starts from: cash on one side, investments on the other.
 *
 * <p>Cash may be negative — an overdraft is a position, and {@code BalancePoint} allows it for the
 * same reason. Investments may not, which {@code BalancePoint} also refuses; catching it here names
 * the port that produced the bad figure rather than the projection that tripped over it.
 */
public record OpeningPosition(Money cash, Money invested) {

    public OpeningPosition {
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(invested, "invested");
        if (cash.currency() != invested.currency()) {
            throw new IllegalArgumentException("Opening cash is in " + cash.currency() + " but investments are in "
                    + invested.currency() + "; FinAssist performs no conversion");
        }
        if (invested.isNegative()) {
            throw new IllegalArgumentException("An opening invested balance cannot be negative but was " + invested);
        }
    }

    public static OpeningPosition zero(Currency currency) {
        return new OpeningPosition(Money.zero(currency), Money.zero(currency));
    }

    public Currency currency() {
        return cash.currency();
    }
}
