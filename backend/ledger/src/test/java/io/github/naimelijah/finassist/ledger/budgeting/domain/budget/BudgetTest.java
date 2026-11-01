package io.github.naimelijah.finassist.ledger.budgeting.domain.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Budget invariants, and the fact that spend is never stored here. */
class BudgetTest {

    private static final BudgetPeriod MARCH = BudgetPeriod.of(YearMonth.of(2026, 3));
    private static final Category GROCERIES = Category.of("Groceries");
    private static final Category TRANSPORT = Category.of("Transport");

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    private static Budget marchBudget() {
        return Budget.of(
                MARCH,
                Currency.ILS,
                List.of(new CategoryLimit(GROCERIES, ils("1500.00")), new CategoryLimit(TRANSPORT, ils("400.00"))));
    }

    @Test
    void Given_limits_in_one_currency_When_a_budget_is_built_Then_it_totals_them() {
        assertThat(marchBudget().total()).isEqualTo(ils("1900.00"));
    }

    @Test
    void Given_two_limits_for_one_category_When_a_budget_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> Budget.of(
                        MARCH,
                        Currency.ILS,
                        List.of(
                                new CategoryLimit(GROCERIES, ils("1500.00")),
                                new CategoryLimit(GROCERIES, ils("200.00")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one limit");
    }

    @Test
    void Given_a_limit_in_another_currency_When_a_budget_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> Budget.of(
                        MARCH, Currency.ILS, List.of(new CategoryLimit(GROCERIES, Money.of("100.00", Currency.USD)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("but the budget is in");
    }

    @Test
    void Given_no_limits_When_a_budget_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> Budget.of(MARCH, Currency.ILS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgets nothing");
    }

    @Test
    void Given_a_limit_of_zero_When_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new CategoryLimit(GROCERIES, Money.zero(Currency.ILS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    /**
     * The public constructor is the path the store reads through, and it takes a bare map of
     * {@link Money}, so {@link CategoryLimit}'s own refusal never runs there.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-5.00"})
    void Given_a_limit_that_is_not_positive_When_a_budget_is_constructed_directly_Then_it_is_refused(String amount) {
        assertThatThrownBy(() -> new Budget(BudgetId.generate(), MARCH, Currency.ILS, Map.of(GROCERIES, ils(amount))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void Given_a_category_name_in_any_case_When_looked_up_Then_it_matches() {
        assertThat(marchBudget().limitFor(Category.of("GROCERIES"))).contains(ils("1500.00"));
    }

    @Test
    void Given_spend_below_the_limit_When_remaining_is_asked_Then_it_is_the_difference() {
        assertThat(marchBudget().remaining(GROCERIES, ils("1200.00"))).contains(ils("300.00"));
    }

    @Test
    void Given_spend_above_the_limit_When_remaining_is_asked_Then_it_is_negative_not_an_error() {
        assertThat(marchBudget().remaining(TRANSPORT, ils("450.00"))).contains(ils("-50.00"));
    }

    @Test
    void Given_an_unbudgeted_category_When_remaining_is_asked_Then_it_is_empty_not_zero() {
        assertThat(marchBudget().remaining(Category.of("Holidays"), ils("10.00")))
                .isEqualTo(Optional.empty());
    }

    @Test
    void Given_a_period_ending_before_it_starts_When_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new BudgetPeriod(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start before it ends");
    }

    @Test
    void Given_a_month_period_When_dates_are_tested_Then_the_end_is_exclusive() {
        assertThat(MARCH.contains(LocalDate.of(2026, 3, 1))).isTrue();
        assertThat(MARCH.contains(LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(MARCH.contains(LocalDate.of(2026, 4, 1))).isFalse();
        assertThat(MARCH.contains(LocalDate.of(2026, 2, 28))).isFalse();
    }

    @Test
    void Given_a_budget_id_When_rendered_Then_it_reads_as_its_value() {
        BudgetId id = BudgetId.generate();

        assertThat(id.toString()).isEqualTo(id.value().toString());
    }

    @Test
    void Given_a_calendar_month_When_a_period_is_built_Then_it_spans_that_month_half_open() {
        BudgetPeriod march = BudgetPeriod.of(YearMonth.of(2026, 3));

        assertThat(march.start()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(march.end()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(march.contains(LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(march.contains(march.end())).isFalse();
    }
}
