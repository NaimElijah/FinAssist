package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code BudgetReview}'s own validation, kept apart from the service that builds one. */
class BudgetReviewTest {

    private static final BudgetPeriod SEPTEMBER = BudgetPeriod.of(YearMonth.of(2026, 9));

    @Test
    void Given_no_categories_When_a_review_is_built_Then_it_is_refused_naming_the_field() {
        assertThatThrownBy(() -> new BudgetReview(
                        BudgetId.generate(), SEPTEMBER, Currency.ILS, null, Map.of(), Money.zero(Currency.ILS)))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("categories");
    }

    @Test
    void Given_no_unbudgeted_spend_When_a_review_is_built_Then_it_is_refused_naming_the_field() {
        assertThatThrownBy(() -> new BudgetReview(
                        BudgetId.generate(), SEPTEMBER, Currency.ILS, List.of(), null, Money.zero(Currency.ILS)))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("unbudgeted");
    }
}
