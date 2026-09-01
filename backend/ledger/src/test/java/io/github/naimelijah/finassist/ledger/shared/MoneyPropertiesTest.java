package io.github.naimelijah.finassist.ledger.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The algebraic laws {@link Money} must obey, over generated amounts rather than chosen ones.
 *
 * <p>Example-based tests pick the cases their author already thought of. These assert the
 * properties instead, across a wide spread of values including every currency's scale.
 *
 * <p><strong>Generation is seeded and therefore deterministic.</strong> The same inputs run on
 * every machine and every CI run, so a failure here is reproducible and this suite can never
 * flake — which is what ADR-016 asks of the domain and what a test of it should honour too. jqwik
 * would be the idiomatic tool; its newest release still requires JUnit Platform 1.x while this
 * module is on Platform 6, so generation is done here with a seeded {@link Random}. See
 * docs/ADRs/ADR-017-test-strategy.md.
 *
 * <p>Amounts are generated as integer <em>minor units</em> and then scaled, not as arbitrary
 * decimals. Generating decimals directly would have {@link Money}'s constructor reject most of
 * them for excess precision, and a property test whose inputs are mostly refused is a constructor
 * test wearing a disguise.
 */
class MoneyPropertiesTest {

    /** Fixed so the suite is reproducible; change it deliberately, never to make a test pass. */
    private static final long SEED = 20260830L;

    private static final int CASES = 100;

    private static Stream<Money> amounts() {
        Random random = new Random(SEED);
        Currency[] currencies = Currency.values();
        return IntStream.range(0, CASES).mapToObj(index -> {
            Currency currency = currencies[random.nextInt(currencies.length)];
            long minorUnits = random.nextLong(-1_000_000_000L, 1_000_000_000L);
            return new Money(BigDecimal.valueOf(minorUnits, currency.minorUnits()), currency);
        });
    }

    /** Pairs sharing a currency, since anything else is refused by design (ADR-012). */
    private static Stream<org.junit.jupiter.params.provider.Arguments> pairs() {
        Random random = new Random(SEED + 1);
        Currency[] currencies = Currency.values();
        return IntStream.range(0, CASES).mapToObj(index -> {
            Currency currency = currencies[random.nextInt(currencies.length)];
            return org.junit.jupiter.params.provider.Arguments.of(money(random, currency), money(random, currency));
        });
    }

    private static Money money(Random random, Currency currency) {
        return new Money(
                BigDecimal.valueOf(random.nextLong(-1_000_000_000L, 1_000_000_000L), currency.minorUnits()), currency);
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void Given_any_amount_When_it_is_normalised_Then_its_scale_is_the_currency_minor_units(Money money) {
        assertThat(money.amount().scale()).isEqualTo(money.currency().minorUnits());
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void Given_any_amount_When_negated_twice_Then_the_original_returns(Money money) {
        assertThat(money.negate().negate()).isEqualTo(money);
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void Given_any_amount_When_zero_is_added_Then_it_is_unchanged(Money money) {
        assertThat(money.plus(Money.zero(money.currency()))).isEqualTo(money);
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void Given_any_amount_When_multiplied_by_one_Then_it_is_unchanged(Money money) {
        assertThat(money.times(BigDecimal.ONE)).isEqualTo(money);
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void Given_any_amount_When_subtracted_from_itself_Then_the_result_is_zero(Money money) {
        assertThat(money.minus(money)).isEqualTo(Money.zero(money.currency()));
        assertThat(money.minus(money).isZero()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void Given_two_amounts_When_added_in_either_order_Then_the_sum_is_the_same(Money left, Money right) {
        assertThat(left.plus(right)).isEqualTo(right.plus(left));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void Given_two_amounts_When_one_is_added_and_removed_Then_the_other_returns(Money left, Money right) {
        assertThat(left.plus(right).minus(right)).isEqualTo(left);
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void Given_two_amounts_When_compared_Then_the_ordering_agrees_with_the_difference(Money left, Money right) {
        int comparison = left.compareTo(right);
        Money difference = left.minus(right);

        if (comparison < 0) {
            assertThat(difference.isNegative()).isTrue();
        } else if (comparison > 0) {
            assertThat(difference.isPositive()).isTrue();
        } else {
            assertThat(difference.isZero()).isTrue();
        }
    }

    /**
     * {@code timesRounded} may round, but only to the currency's own precision — so it can never
     * land more than <em>half</em> a minor unit away from the exact product.
     *
     * <p>Half, not a whole unit: the looser bound this originally asserted is satisfied by
     * {@code CEILING} and {@code UP} as well as {@code HALF_UP}, so it could not detect the
     * rounding mode being changed.
     */
    @ParameterizedTest
    @MethodSource("amounts")
    void Given_any_amount_When_scaled_with_rounding_Then_it_stays_within_half_a_minor_unit(Money money) {
        BigDecimal multiplier = new BigDecimal("1.005");
        Money rounded = money.timesRounded(multiplier);
        BigDecimal exact = money.amount().multiply(multiplier);
        BigDecimal halfAMinorUnit =
                BigDecimal.ONE.movePointLeft(money.currency().minorUnits()).divide(BigDecimal.TWO);

        assertThat(rounded.currency()).isEqualTo(money.currency());
        assertThat(rounded.amount().subtract(exact).abs()).isLessThanOrEqualTo(halfAMinorUnit);
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void Given_an_amount_that_needs_no_rounding_When_scaled_Then_both_multiplications_agree(Money money) {
        BigDecimal multiplier = BigDecimal.valueOf(3);

        assertThat(money.times(multiplier)).isEqualTo(money.timesRounded(multiplier));
    }

    @ParameterizedTest
    @MethodSource("amounts")
    void Given_any_amount_When_it_is_rendered_Then_the_text_carries_the_currency_and_full_scale(Money money) {
        assertThat(money.toString())
                .isEqualTo(money.amount().setScale(money.currency().minorUnits(), RoundingMode.UNNECESSARY) + " "
                        + money.currency().name());
    }
}
