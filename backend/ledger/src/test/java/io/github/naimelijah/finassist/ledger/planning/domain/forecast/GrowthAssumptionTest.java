package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A growth rate is bounded from above as well as below, in size and in precision (ADR-072), so no
 * scenario can compound a projection into figures nobody could hold.
 */
class GrowthAssumptionTest {

    /** Far longer than any refusal needs, and far shorter than a rate written out digit by digit. */
    private static final int MESSAGE_BOUND = 1_000;

    @Test
    void Given_a_rate_of_exactly_the_maximum_When_an_assumption_is_built_Then_it_is_allowed() {
        assertThat(GrowthAssumption.perStep("1").ratePerStep()).isEqualByComparingTo(GrowthAssumption.MAXIMUM_RATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0000000001", "1.5", "5", "100"})
    void Given_a_rate_above_the_maximum_When_an_assumption_is_built_Then_it_is_refused(String rate) {
        assertThatThrownBy(() -> GrowthAssumption.perStep(rate))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1");
    }

    /**
     * The refusal names the bound, never the rate: a rate is caller's text, and one written out in
     * full can be as long as the request that carried it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1E+2147483647", "1E+1000000"})
    void Given_a_rate_at_a_vast_exponent_above_the_maximum_When_an_assumption_is_built_Then_it_is_refused_briefly(
            String rate) {
        assertThatThrownBy(() -> GrowthAssumption.perStep(rate))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1")
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .hasSizeLessThan(MESSAGE_BOUND)
                        .doesNotContain(rate));
    }

    @Test
    void Given_a_rate_with_exactly_the_maximum_decimal_places_When_an_assumption_is_built_Then_it_is_allowed() {
        assertThat(GrowthAssumption.perStep("0.0000000001").ratePerStep().scale())
                .isEqualTo(GrowthAssumption.MAXIMUM_DECIMAL_PLACES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00000000001", "0.12345678901", "-0.00000000001", "1E-2147483647"})
    void Given_a_rate_with_more_than_the_maximum_decimal_places_When_an_assumption_is_built_Then_it_is_refused_briefly(
            String rate) {
        assertThatThrownBy(() -> GrowthAssumption.perStep(rate))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 10 decimal places")
                .satisfies(refusal -> assertThat(refusal.getMessage()).hasSizeLessThan(MESSAGE_BOUND));
    }

    /** Decimal places are counted once trailing zeros are dropped: 0.10000000000 is 0.1. */
    @Test
    void Given_a_rate_whose_extra_decimal_places_are_trailing_zeros_When_an_assumption_is_built_Then_it_is_allowed() {
        assertThat(GrowthAssumption.perStep("0.10000000000").ratePerStep()).isEqualTo(new BigDecimal("0.1"));
    }

    /** The upper bound is checked before trailing zeros are dropped, so the order cannot let it slip. */
    @Test
    void Given_the_maximum_written_with_trailing_zeros_When_an_assumption_is_built_Then_it_is_allowed() {
        assertThat(GrowthAssumption.perStep("1.000").ratePerStep()).isEqualTo(BigDecimal.ONE);
    }
}
