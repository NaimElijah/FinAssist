package io.github.naimelijah.finassist.ledger.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An exact monetary amount in one currency.
 *
 * <p>Backed by {@link BigDecimal}. There is no constructor taking a {@code double} and there never
 * will be: a binary floating-point type cannot represent 0.10, and a ledger that loses a cent per
 * thousand transactions is wrong in a way no test written after the fact will find.
 * {@code LedgerArchitectureTest.money_is_never_floating_point} fails the build if a floating-point
 * field appears anywhere in a domain package.
 *
 * <p>The amount is normalised to the currency's minor units on construction, so {@code 12.5 ILS} and
 * {@code 12.50 ILS} are the same value and compare equal. An amount carrying more precision than the
 * currency admits is rejected rather than rounded — rounding here would be a silent decision about
 * someone's money.
 *
 * <p>Arithmetic is closed over a single currency. Combining two currencies throws
 * {@link CurrencyMismatchException}; see ADR-012 for why no conversion exists.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        try {
            amount = amount.setScale(currency.minorUnits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException cause) {
            throw new IllegalArgumentException(
                    "Amount "
                            + amount.toPlainString()
                            + " carries more precision than "
                            + currency
                            + " admits ("
                            + currency.minorUnits()
                            + " decimal places); round it deliberately before constructing Money",
                    cause);
        }
    }

    /**
     * Builds an amount from its decimal text, e.g. {@code Money.of("12.50", Currency.ILS)}.
     *
     * <p>Text rather than a numeric literal on purpose: {@code new BigDecimal(12.50)} captures the
     * floating-point approximation of 12.5, which is not 12.50.
     */
    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** Multiplies by a count, for a quantity of units. Never by another Money — that is not money. */
    public Money times(BigDecimal multiplier) {
        Objects.requireNonNull(multiplier, "multiplier");
        return new Money(amount.multiply(multiplier), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Orders two amounts of the same currency.
     *
     * @throws CurrencyMismatchException if the currencies differ; there is no cross-currency order
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
