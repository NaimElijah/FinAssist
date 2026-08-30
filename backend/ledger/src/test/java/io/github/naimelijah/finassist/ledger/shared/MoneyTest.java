package io.github.naimelijah.finassist.ledger.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The Money contract.
 *
 * <p>These cases are mirrored by {@code backend/assistant/tests/test_money.py}. ADR-010 accepts that
 * Money is implemented once per language rather than shared, and names equivalent tests on both
 * sides as the thing that keeps the two honest. Changing behaviour here without changing it there is
 * the drift this pair exists to catch.
 */
class MoneyTest {

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
}
