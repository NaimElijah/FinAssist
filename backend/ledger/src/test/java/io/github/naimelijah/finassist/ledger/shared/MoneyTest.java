package io.github.naimelijah.finassist.ledger.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Money contract.
 *
 * <p>These cases are mirrored by {@code backend/assistant/tests/test_money.py}. ADR-010 accepts that
 * Money is implemented once per language rather than shared, and names equivalent tests on both
 * sides as the thing that keeps the two honest. Changing behaviour here without changing it there is
 * the drift this pair exists to catch.
 */
class MoneyTest {

    /** Far longer than any refusal needs, and far shorter than an amount expanded digit by digit. */
    private static final int MESSAGE_BOUND = 1_000;

    @Test
    void Given_an_amount_with_fewer_decimals_When_constructed_Then_it_is_normalised_to_the_currency() {
        assertThat(Money.of("12.5", Currency.ILS).amount()).isEqualTo(new BigDecimal("12.50"));
        assertThat(Money.of("12.5", Currency.ILS)).isEqualTo(Money.of("12.50", Currency.ILS));
    }

    @Test
    void Given_an_amount_with_too_many_decimals_When_constructed_Then_it_is_rejected() {
        assertThatThrownBy(() -> Money.of("12.505", Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more precision");
    }

    /**
     * Refused for its precision like any other amount, and with a message of ordinary length. The
     * refusal once wrote the amount out in full: for a scale at Java's int limit that is longer than
     * any String can be, so an {@code OutOfMemoryError} escaped in place of the refusal, and for
     * {@code 1E-1000000} it is a million characters.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1E-2147483647", "1E-1000000"})
    void Given_an_amount_at_a_vast_negative_exponent_When_parsed_Then_it_is_refused_with_a_short_message(
            String amount) {
        assertThatThrownBy(() -> Money.of(amount, Currency.USD))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more precision")
                .satisfies(refusal -> assertThat(refusal.getMessage()).hasSizeLessThan(MESSAGE_BOUND));
    }

    @Test
    void Given_a_zero_decimal_currency_When_given_decimals_Then_it_is_rejected() {
        assertThatThrownBy(() -> Money.of("100.50", Currency.JPY)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.of("100", Currency.JPY).amount()).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void Given_two_amounts_in_one_currency_When_added_Then_they_sum() {
        assertThat(Money.of("10.00", Currency.ILS).plus(Money.of("2.50", Currency.ILS)))
                .isEqualTo(Money.of("12.50", Currency.ILS));
    }

    @Test
    void Given_two_currencies_When_added_Then_it_is_refused() {
        assertThatThrownBy(() -> Money.of("10.00", Currency.ILS).plus(Money.of("10.00", Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("no conversion");
    }

    @Test
    void Given_two_currencies_When_compared_Then_it_is_refused() {
        assertThatThrownBy(() -> Money.of("10.00", Currency.ILS).compareTo(Money.of("10.00", Currency.EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void Given_an_amount_When_negated_Then_the_sign_flips_and_the_currency_holds() {
        Money negated = Money.of("12.50", Currency.ILS).negate();
        assertThat(negated).isEqualTo(Money.of("-12.50", Currency.ILS));
        assertThat(negated.isNegative()).isTrue();
        assertThat(negated.negate()).isEqualTo(Money.of("12.50", Currency.ILS));
    }

    /**
     * The mirror of the Python case that found unary minus rounding to 28 significant digits.
     * {@code BigDecimal.negate} never rounds, so this side always passed; present so the pair
     * covers it (#70).
     */
    @Test
    void Given_an_amount_past_28_digits_When_negated_Then_every_digit_is_kept() {
        Money amount = Money.of("99999999999999999999999999999.99", Currency.USD);

        assertThat(amount.negate()).hasToString("-99999999999999999999999999999.99 USD");
    }

    /** {@code BigDecimal} has no negative zero; the Python mirror pins that it does not grow one. */
    @Test
    void Given_zero_When_negated_Then_it_is_zero_without_a_sign() {
        assertThat(Money.zero(Currency.USD).negate()).hasToString("0.00 USD");
    }

    @Test
    void Given_zero_When_asked_Then_it_is_zero_and_neither_positive_nor_negative() {
        Money zero = Money.zero(Currency.ILS);
        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isPositive()).isFalse();
        assertThat(zero.isNegative()).isFalse();
    }

    @Test
    void Given_an_amount_When_multiplied_by_a_quantity_Then_it_scales() {
        assertThat(Money.of("1.25", Currency.USD).times(new BigDecimal("4"))).isEqualTo(Money.of("5.00", Currency.USD));
    }

    @Test
    void Given_a_very_large_amount_When_constructed_Then_it_is_accepted() {
        // Mirrored deliberately: the Python kernel raised decimal.InvalidOperation here, past its
        // default context precision, where BigDecimal has no such limit.
        // isEqualTo, not isEqualByComparingTo: the latter ignores scale, which is the one thing
        // this assertion should pin. The Python mirror had the same blind spot for the same
        // reason and is fixed alongside.
        assertThat(Money.of("1E+30", Currency.USD).amount())
                .isEqualTo(new BigDecimal("1000000000000000000000000000000.00"));
    }

    /**
     * The mirror of {@code Given_an_amount_past_a_million_integer_digits_When_constructed_Then_it_is_accepted}:
     * once as an exponent past a million, once as a million and two digits written out. Python's default
     * context stops the exponent there; {@code BigDecimal} has no such limit, so this side always passed.
     * The written-out amount is built from its digits rather than parsed from text, because {@code
     * BigDecimal} parses a million digits in time that grows with the square of their number.
     */
    @ParameterizedTest
    @MethodSource("amountsPastAMillionIntegerDigits")
    void Given_an_amount_past_a_million_integer_digits_When_constructed_Then_it_is_accepted(BigDecimal amount) {
        Money money = new Money(amount, Currency.USD);

        assertThat(money.amount()).isEqualByComparingTo(amount);
        assertThat(money.amount().scale()).isEqualTo(Currency.USD.minorUnits());
    }

    static Stream<BigDecimal> amountsPastAMillionIntegerDigits() {
        return Stream.of(new BigDecimal("1E+1000000"), new BigDecimal(BigInteger.TEN.pow(1_000_001)));
    }

    /** The mirror of {@code Given_a_product_past_a_million_integer_digits_When_times_rounded_Then_it_is_accepted}. */
    @Test
    void Given_a_product_past_a_million_integer_digits_When_times_rounded_Then_it_is_accepted() {
        Money product = Money.of("1.00", Currency.USD).timesRounded(new BigDecimal("1E+1000000"));

        assertThat(product.amount()).isEqualByComparingTo(new BigDecimal("1E+1000000"));
    }

    @Test
    void Given_a_fractional_multiplier_When_times_rounded_Then_it_rounds_half_up() {
        assertThat(Money.of("12.33", Currency.USD).timesRounded(new BigDecimal("1.5")))
                .isEqualTo(Money.of("18.50", Currency.USD));
    }

    @Test
    void Given_a_multiplication_needing_rounding_When_applied_Then_it_is_rejected() {
        assertThatThrownBy(() -> Money.of("0.01", Currency.USD).times(new BigDecimal("0.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code BigDecimal.multiply} refuses a product whose scale overflows an int with a bare
     * {@code ArithmeticException}, which both multiplications' contracts leave out. A zero
     * multiplier is refused too: {@code BigDecimal} checks the scale whenever the amount is not
     * zero, whatever it is multiplied by. Mirrored in {@code tests/test_money.py}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1E-2147483647", "0E-2147483647"})
    void Given_a_product_scale_past_an_int_When_multiplied_Then_it_is_refused_with_a_short_message(String multiplier) {
        Money amount = Money.of("12.50", Currency.USD);

        assertThatThrownBy(() -> amount.times(new BigDecimal(multiplier)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more precision")
                .satisfies(refusal -> assertThat(refusal.getMessage()).hasSizeLessThan(MESSAGE_BOUND));
    }

    /** The same refusal from {@code timesRounded}. Mirrored in {@code tests/test_money.py}. */
    @ParameterizedTest
    @ValueSource(strings = {"1E-2147483647", "0E-2147483647"})
    void Given_a_product_scale_past_an_int_When_times_rounded_Then_it_is_refused_with_a_short_message(
            String multiplier) {
        Money amount = Money.of("12.50", Currency.USD);

        assertThatThrownBy(() -> amount.timesRounded(new BigDecimal(multiplier)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more precision")
                .satisfies(refusal -> assertThat(refusal.getMessage()).hasSizeLessThan(MESSAGE_BOUND));
    }

    /**
     * Here the product's scale fits an int, but rounding it to cents needs a power of ten past
     * {@code BigInteger}'s range, which {@code setScale} refuses with the same bare
     * {@code ArithmeticException}. Not mirrored: Python's {@code Decimal} has no such bound and
     * rounds the product to {@code 0.00}, a divergence of the same kind as an amount past
     * {@code BigInteger}'s range, which the assistant's {@code Money.of} documents.
     */
    @Test
    void Given_a_product_too_precise_to_round_When_times_rounded_Then_it_is_refused_with_a_short_message() {
        Money amount = Money.of("12.50", Currency.USD);

        assertThatThrownBy(() -> amount.timesRounded(new BigDecimal("1E-2147483645")))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more precision")
                .satisfies(refusal -> assertThat(refusal.getMessage()).hasSizeLessThan(MESSAGE_BOUND));
    }

    /**
     * Asserted for {@code plus} and {@code compareTo} since the beginning, and never for
     * {@code minus} - mutation testing found that deleting its {@code requireSameCurrency} call
     * broke nothing. The same blind spot existed in the Python mirror.
     */
    @Test
    void Given_two_currencies_When_subtracted_Then_it_is_refused() {
        assertThatThrownBy(() -> Money.of("10.00", Currency.ILS).minus(Money.of("3.00", Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("no conversion");
    }

    @Test
    void Given_an_amount_that_is_not_zero_When_asked_Then_it_says_so() {
        assertThat(Money.of("0.01", Currency.ILS).isZero()).isFalse();
        assertThat(Money.of("-0.01", Currency.ILS).isZero()).isFalse();
        assertThat(Money.of("12.50", Currency.ILS).isPositive()).isTrue();
    }

    /**
     * The mirror of {@code Given_a_very_large_product_When_times_rounded_Then_it_is_accepted}.
     * {@code BigDecimal} has no context precision to outgrow, so this side always passed - which
     * is exactly why the Python divergence went unnoticed. Present so the pair covers it.
     */
    @Test
    void Given_a_very_large_product_When_times_rounded_Then_it_is_accepted() {
        Money huge = Money.of("1E+30", Currency.USD);

        assertThat(huge.timesRounded(new BigDecimal("1E+30")).toString()).isEqualTo("1" + "0".repeat(60) + ".00 USD");
    }
}
