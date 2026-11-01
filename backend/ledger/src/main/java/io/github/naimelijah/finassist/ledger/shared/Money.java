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
 * field appears anywhere in a domain package or in this one.
 *
 * <p>The amount is normalised to the currency's minor units on construction, so {@code 12.5 ILS} and
 * {@code 12.50 ILS} are the same value and compare equal. An amount carrying more precision than the
 * currency admits is rejected rather than rounded — rounding here would be a silent decision about
 * someone's money.
 *
 * <p>Arithmetic is closed over a single currency. Combining two currencies throws
 * {@link CurrencyMismatchException}; see ADR-012 for why no conversion exists.
 *
 * <p>Deliberately <strong>not</strong> {@link Comparable}. That interface promises a total order
 * over the type, and there is no order between an ILS amount and a USD one. Implementing it would
 * put a throwing {@code compareTo} inside {@code TreeSet}, {@code Collections.sort} and
 * {@code Stream.sorted}, where the failure surfaces from JDK internals with nothing in the
 * signature having warned the caller. {@link #compareTo(Money)} is a plain method instead: usable
 * within one currency, and honest about being partial.
 */
public record Money(BigDecimal amount, Currency currency) {

    /**
     * Normalises {@code amount} to {@code currency}'s minor units.
     *
     * @param amount the exact amount; a scale below the currency's is padded with zeros, and a
     *     negative amount is allowed
     * @param currency the currency the amount is held in
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if {@code amount} carries more decimal places than
     *     {@code currency} admits, so that it would have to be rounded
     */
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        try {
            amount = amount.setScale(currency.minorUnits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException cause) {
            // toString, never toPlainString: an amount refused here has an exponent far from the
            // currency's scale, in either direction - more decimal places than it admits, as in
            // 1E-1000000, or so many places the other way that setScale overflows an int, as in
            // 1E+2147483647. toPlainString writes either out digit by digit: a million characters
            // for the first, and an OutOfMemoryError in place of this refusal for the second.
            throw new IllegalArgumentException(
                    "Amount "
                            + amount.toString()
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
     *
     * @param amount a decimal literal as {@link BigDecimal#BigDecimal(String)} reads it
     * @param currency the currency the amount is held in
     * @throws NumberFormatException if {@code amount} is not a decimal literal
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if {@code amount} carries more decimal places than
     *     {@code currency} admits
     */
    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    /** Returns the zero amount in {@code currency}, at the currency's scale. */
    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * Returns the sum of this amount and {@code other}.
     *
     * @throws CurrencyMismatchException if the currencies differ
     * @throws NullPointerException if {@code other} is null
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Returns this amount less {@code other}.
     *
     * @throws CurrencyMismatchException if the currencies differ
     * @throws NullPointerException if {@code other} is null
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Returns this amount with its sign flipped; zero stays zero, as {@link BigDecimal} has no signed zero. */
    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /**
     * Multiplies by a count, exactly. Never by another Money — that is not money.
     *
     * @throws IllegalArgumentException if the product needs rounding to fit the currency
     * @throws NullPointerException if {@code multiplier} is null
     */
    public Money times(BigDecimal multiplier) {
        Objects.requireNonNull(multiplier, "multiplier");
        return new Money(amount.multiply(multiplier), currency);
    }

    /**
     * Multiplies by a count, rounding the product to the currency's minor units.
     *
     * <p>The one place rounding is allowed, and it is explicit in the name. A fractional quantity —
     * 1.5 units at 12.33 — has a mathematically exact product of 18.495 that no currency with two
     * decimal places can express, so a total cost has to be rounded somewhere. Doing it here, half
     * up, in a method the caller has to choose, beats {@link #times} throwing on perfectly ordinary
     * fractional holdings.
     *
     * @throws NullPointerException if {@code multiplier} is null
     */
    public Money timesRounded(BigDecimal multiplier) {
        Objects.requireNonNull(multiplier, "multiplier");
        BigDecimal product = amount.multiply(multiplier);
        return new Money(product.setScale(currency.minorUnits(), RoundingMode.HALF_UP), currency);
    }

    /** Returns whether the amount is zero. */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /** Returns whether the amount is greater than zero. */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** Returns whether the amount is less than zero. */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Orders two amounts of the same currency.
     *
     * <p>Not an implementation of {@link Comparable}: see the note on this class.
     *
     * @return negative, zero or positive as this amount is less than, equal to or greater than
     *     {@code other}
     * @throws CurrencyMismatchException if the currencies differ; there is no cross-currency order
     * @throws NullPointerException if {@code other} is null
     */
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    /** Returns the amount in plain notation, a space and the currency code, e.g. {@code 12.50 ILS}. */
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
