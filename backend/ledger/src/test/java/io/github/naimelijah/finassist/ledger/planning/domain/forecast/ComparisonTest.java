package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Comparing strategies: differences reported, mismatches refused, no winner nominated. */
class ComparisonTest {

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final Horizon QUARTER = new Horizon(JANUARY, APRIL);

    private static final String BASELINE = "Do nothing";
    private static final String SPEND = "Buy the laptop";

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    private static Projection baselineProjection() {
        return Projection.of(
                Scenario.baseline(BASELINE, Currency.ILS, GrowthAssumption.none()),
                QUARTER,
                Cadence.MONTHLY,
                ils("1000.00"),
                Money.zero(Currency.ILS));
    }

    private static Projection spendingProjection() {
        PlannedFlow laptop = PlannedFlow.plan(
                "New laptop", ils("400.00"), Direction.OUTFLOW, LocalDate.of(2026, 2, 1), new Recurrence.Once());
        return Projection.of(
                Scenario.of(SPEND, Currency.ILS, List.of(laptop), GrowthAssumption.none()),
                QUARTER,
                Cadence.MONTHLY,
                ils("1000.00"),
                Money.zero(Currency.ILS));
    }

    private static Comparison aComparison() {
        Map<String, Projection> named = new LinkedHashMap<>();
        named.put(BASELINE, baselineProjection());
        named.put(SPEND, spendingProjection());
        return Comparison.of(named);
    }

    @Test
    void Given_a_baseline_and_a_spending_scenario_When_diverged_Then_the_difference_is_the_spend() {
        Map<String, List<Divergence>> divergence = aComparison().divergenceFrom(BASELINE);

        assertThat(divergence).containsOnlyKeys(SPEND);
        assertThat(divergence.get(SPEND))
                .extracting(Divergence::cash)
                .containsExactly(Money.zero(Currency.ILS), Money.zero(Currency.ILS), ils("-400.00"), ils("-400.00"));
    }

    @Test
    void Given_a_comparison_When_asked_for_end_states_Then_each_scenario_is_named() {
        assertThat(aComparison().endStates())
                .containsOnlyKeys(BASELINE, SPEND)
                .extractingByKey(SPEND)
                .extracting(BalancePoint::cash)
                .isEqualTo(ils("600.00"));
    }

    @Test
    void Given_two_projections_in_different_currencies_When_compared_Then_it_is_refused() {
        Projection dollars = Projection.of(
                Scenario.baseline("Dollars", Currency.USD, GrowthAssumption.none()),
                QUARTER,
                Cadence.MONTHLY,
                Money.of("1000.00", Currency.USD),
                Money.zero(Currency.USD));

        Map<String, Projection> mixed = new LinkedHashMap<>();
        mixed.put(BASELINE, baselineProjection());
        mixed.put("Dollars", dollars);

        assertThatThrownBy(() -> Comparison.of(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("performs no conversion");
    }

    @Test
    void Given_two_projections_over_different_horizons_When_compared_Then_it_is_refused() {
        Projection halfYear = Projection.of(
                Scenario.baseline("Longer", Currency.ILS, GrowthAssumption.none()),
                new Horizon(JANUARY, LocalDate.of(2026, 7, 1)),
                Cadence.MONTHLY,
                ils("1000.00"),
                Money.zero(Currency.ILS));

        Map<String, Projection> mixed = new LinkedHashMap<>();
        mixed.put(BASELINE, baselineProjection());
        mixed.put("Longer", halfYear);

        assertThatThrownBy(() -> Comparison.of(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no honest way to align them");
    }

    @Test
    void Given_projections_stepping_at_different_cadences_When_compared_Then_it_is_refused() {
        Projection yearlySteps = Projection.of(
                Scenario.baseline("Yearly", Currency.ILS, GrowthAssumption.none()),
                new Horizon(JANUARY, LocalDate.of(2027, 1, 1)),
                Cadence.YEARLY,
                ils("1000.00"),
                Money.zero(Currency.ILS));
        Projection monthlySteps = Projection.of(
                Scenario.baseline("Monthly", Currency.ILS, GrowthAssumption.none()),
                new Horizon(JANUARY, LocalDate.of(2027, 1, 1)),
                Cadence.MONTHLY,
                ils("1000.00"),
                Money.zero(Currency.ILS));

        Map<String, Projection> mixed = new LinkedHashMap<>();
        mixed.put("Yearly", yearlySteps);
        mixed.put("Monthly", monthlySteps);

        assertThatThrownBy(() -> Comparison.of(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("steps");
    }

    @Test
    void Given_a_single_projection_When_compared_Then_it_is_refused() {
        assertThatThrownBy(() -> Comparison.of(Map.of(BASELINE, baselineProjection())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void Given_an_unknown_name_When_asked_for_its_projection_Then_it_is_refused() {
        assertThatThrownBy(() -> aComparison().projection("Something else"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No scenario named");
    }

    @Test
    void Given_a_blank_name_When_a_comparison_is_built_Then_it_is_refused() {
        Map<String, Projection> unnamed = new LinkedHashMap<>();
        unnamed.put(BASELINE, baselineProjection());
        unnamed.put("  ", spendingProjection());

        assertThatThrownBy(() -> Comparison.of(unnamed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be named");
    }

    @Test
    void Given_a_comparison_When_read_Then_it_keeps_the_order_it_was_given() {
        assertThat(aComparison().names()).containsExactly(BASELINE, SPEND);
    }
}
