package io.github.naimelijah.finassist.ledger.budgeting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetCommand;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.CategoryLimit;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of defining a budget and collecting rules.
 *
 * <p>Deliberately thin, because the service is. What a budget may be is {@code Budget.of}'s business,
 * and these cases pin that the service adds nothing and hides nothing — a refused budget must not be
 * stored, and rule order must survive the round trip because a review depends on it.
 */
class BudgetServiceTest {

    private static final BudgetPeriod MARCH = BudgetPeriod.of(YearMonth.of(2026, 3));
    private static final Category GROCERIES = Category.of("groceries");
    private static final Category RENT = Category.of("rent");

    private final BudgetRepository budgets = mock(BudgetRepository.class);
    private final CategorisationRuleRepository rules = mock(CategorisationRuleRepository.class);
    private final BudgetService service = new BudgetService(budgets, rules);

    @Test
    void Given_limits_for_a_period_When_a_budget_is_defined_Then_it_is_stored_with_a_generated_id() {
        Budget defined = service.define(
                new DefineBudgetCommand(MARCH, Currency.ILS, List.of(new CategoryLimit(GROCERIES, ils("1200.00")))));

        verify(budgets).save(defined);
        assertThat(defined.id()).isNotNull();
        assertThat(defined.limitFor(GROCERIES)).contains(ils("1200.00"));
    }

    @Test
    void Given_a_category_limited_twice_When_defined_Then_the_domain_refuses_it_and_nothing_is_stored() {
        assertThatThrownBy(() -> service.define(new DefineBudgetCommand(
                        MARCH,
                        Currency.ILS,
                        List.of(
                                new CategoryLimit(GROCERIES, ils("1200.00")),
                                new CategoryLimit(GROCERIES, ils("5.00"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one limit");

        verify(budgets, never()).save(any());
    }

    @Test
    void Given_a_limit_in_another_currency_When_defined_Then_it_is_refused() {
        assertThatThrownBy(() -> service.define(new DefineBudgetCommand(
                        MARCH, Currency.ILS, List.of(new CategoryLimit(GROCERIES, Money.of("1200.00", Currency.USD))))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(budgets, never()).save(any());
    }

    @Test
    void Given_no_limits_at_all_When_defined_Then_it_is_refused() {
        assertThatThrownBy(() -> service.define(new DefineBudgetCommand(MARCH, Currency.ILS, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgets nothing");

        verify(budgets, never()).save(any());
    }

    @Test
    void Given_two_budgets_When_both_are_defined_Then_both_are_stored_separately() {
        Budget first = service.define(
                new DefineBudgetCommand(MARCH, Currency.ILS, List.of(new CategoryLimit(GROCERIES, ils("1200.00")))));
        Budget second = service.define(new DefineBudgetCommand(
                BudgetPeriod.of(YearMonth.of(2026, 4)),
                Currency.ILS,
                List.of(new CategoryLimit(RENT, ils("4000.00")))));

        assertThat(first.id()).isNotEqualTo(second.id());
        verify(budgets).save(first);
        verify(budgets).save(second);
    }

    /**
     * A review takes the first matching rule, so the order rules come back in decides the answer.
     * What the service owes is to hand them over in the order it was given them; that the store then
     * returns them in that order is the {@code ORDER BY ordinal} asserted in
     * {@code JdbcCategorisationRuleRepositoryIT}.
     */
    @Test
    void Given_rules_added_in_order_When_they_are_stored_Then_the_order_is_preserved() {
        CategorisationRule coffee = new CategorisationRule("coffee", Category.of("coffee"));
        CategorisationRule eatingOut = new CategorisationRule("co", Category.of("eating out"));

        service.add(coffee);
        service.add(eatingOut);

        org.mockito.InOrder added = inOrder(rules);
        added.verify(rules).add(coffee);
        added.verify(rules).add(eatingOut);
    }

    /** Listing derives nothing of its own - ordering is the repository's job. */
    @Test
    void Given_budgets_stored_When_listed_Then_they_come_back_in_the_repositorys_order() {
        Budget march = Budget.of(MARCH, Currency.ILS, List.of(new CategoryLimit(GROCERIES, ils("1200.00"))));
        Budget april = Budget.of(
                BudgetPeriod.of(YearMonth.of(2026, 4)), Currency.ILS, List.of(new CategoryLimit(RENT, ils("4000.00"))));
        when(budgets.all()).thenReturn(List.of(april, march));

        assertThat(service.all()).containsExactly(april, march);
    }

    /**
     * A review takes the first matching rule, so passing the store's order through unchanged is the
     * whole contract.
     */
    @Test
    void Given_rules_stored_When_listed_Then_they_come_back_in_the_repositorys_order() {
        CategorisationRule coffee = new CategorisationRule("coffee", Category.of("coffee"));
        CategorisationRule eatingOut = new CategorisationRule("co", Category.of("eating out"));
        when(rules.all()).thenReturn(List.of(coffee, eatingOut));

        assertThat(service.rules()).containsExactly(coffee, eatingOut);
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
