package io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.CategoryLimit;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What budgeting's two repositories promise, asserted against the store.
 *
 * <p>Rule order is the claim worth a database. {@code CategorisationRuleRepository.all()} says
 * insertion order is part of its contract, because a review takes the first matching rule and the
 * order therefore decides the answer. Nothing in Java can promise that; an identity column and an
 * {@code ORDER BY} can.
 */
@SpringBootTest
class JdbcBudgetingRepositoriesIT extends PostgresFixture {

    private static final BudgetPeriod MARCH = BudgetPeriod.of(YearMonth.of(2026, 3));
    private static final Category GROCERIES = Category.of("groceries");
    private static final Category RENT = Category.of("rent");

    /** PostgreSQL compares {@code uuid} byte by byte, so these two order the way they read. */
    private static final UUID SMALLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID LARGER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private CategorisationRuleRepository rules;

    @Test
    void Given_a_budget_When_it_is_saved_Then_it_is_read_back_with_every_limit() {
        Budget budget = Budget.of(
                MARCH,
                Currency.ILS,
                List.of(new CategoryLimit(GROCERIES, ils("1200.00")), new CategoryLimit(RENT, ils("4000.00"))));

        budgets.save(budget);

        assertThat(budgets.findById(budget.id())).contains(budget);
    }

    @Test
    void Given_no_such_budget_When_it_is_looked_up_Then_nothing_is_found() {
        assertThat(budgets.findById(BudgetId.generate())).isEmpty();
    }

    /**
     * A budget is intent rather than a record of what happened, so redefining one is a legal write -
     * ADR-020's append-only obligation binds the journal and stops there (ADR-024). What must not
     * happen is a limit surviving a redefinition that dropped it.
     */
    @Test
    void Given_a_budget_saved_again_with_fewer_limits_When_read_back_Then_the_dropped_one_is_gone() {
        Budget original = Budget.of(
                MARCH,
                Currency.ILS,
                List.of(new CategoryLimit(GROCERIES, ils("1200.00")), new CategoryLimit(RENT, ils("4000.00"))));
        budgets.save(original);
        Budget narrowed = new Budget(original.id(), MARCH, Currency.ILS, java.util.Map.of(GROCERIES, ils("900.00")));

        budgets.save(narrowed);

        assertThat(budgets.findById(original.id())).contains(narrowed);
        assertThat(budgets.findById(original.id()).orElseThrow().limitFor(RENT)).isEmpty();
    }

    /** The contract the port states in as many words: the order rules were added in. */
    @Test
    void Given_rules_added_in_an_order_When_they_are_read_back_Then_the_order_is_preserved() {
        CategorisationRule coffee = new CategorisationRule("coffee", Category.of("coffee"));
        CategorisationRule eatingOut = new CategorisationRule("co", Category.of("eating out"));
        CategorisationRule supermarket = new CategorisationRule("superpharm", GROCERIES);

        rules.add(coffee);
        rules.add(eatingOut);
        rules.add(supermarket);

        assertThat(rules.all()).containsExactly(coffee, eatingOut, supermarket);
    }

    @Test
    void Given_no_rules_at_all_When_they_are_read_Then_the_answer_is_empty_rather_than_null() {
        assertThat(rules.all()).isEmpty();
    }

    /**
     * Two rules may share a fragment or a category; only their position separates them. A primary key
     * over the pair would silently drop the second and change what a review answers.
     */
    @Test
    void Given_two_rules_sharing_a_category_When_they_are_read_back_Then_both_are_there() {
        rules.add(new CategorisationRule("superpharm", GROCERIES));
        rules.add(new CategorisationRule("shufersal", GROCERIES));

        assertThat(rules.all()).hasSize(2);
    }

    /**
     * {@code BudgetRepository.all}'s contract: the most recent period leads, and each budget comes
     * back with the limits it was saved with - the same {@code limitsOf} helper {@code findById}
     * uses.
     */
    @Test
    void Given_budgets_for_three_periods_When_all_are_listed_Then_the_most_recent_period_leads_with_limits_intact() {
        Budget august = Budget.of(
                BudgetPeriod.of(YearMonth.of(2026, 8)),
                Currency.ILS,
                List.of(new CategoryLimit(GROCERIES, ils("1200.00"))));
        Budget september = Budget.of(
                BudgetPeriod.of(YearMonth.of(2026, 9)), Currency.ILS, List.of(new CategoryLimit(RENT, ils("4000.00"))));
        Budget july = Budget.of(
                BudgetPeriod.of(YearMonth.of(2026, 7)),
                Currency.ILS,
                List.of(new CategoryLimit(GROCERIES, ils("900.00"))));
        budgets.save(august);
        budgets.save(september);
        budgets.save(july);

        assertThat(budgets.all()).containsExactly(september, august, july);
    }

    /**
     * The port's tie-break: two budgets for one period come back by id. They are saved larger id first,
     * so a store that returned them in insertion order would fail this.
     */
    @Test
    void Given_two_budgets_for_one_period_saved_larger_id_first_When_all_are_listed_Then_the_smaller_id_leads() {
        Budget larger = new Budget(new BudgetId(LARGER_ID), MARCH, Currency.ILS, Map.of(GROCERIES, ils("1200.00")));
        Budget smaller = new Budget(new BudgetId(SMALLER_ID), MARCH, Currency.ILS, Map.of(RENT, ils("4000.00")));
        budgets.save(larger);
        budgets.save(smaller);

        assertThat(budgets.all()).containsExactly(smaller, larger);
    }

    @Test
    void Given_no_budgets_at_all_When_they_are_listed_Then_the_answer_is_empty_rather_than_null() {
        assertThat(budgets.all()).isEmpty();
    }

    @Test
    void Given_a_budget_When_read_over_an_independent_connection_Then_it_is_still_there() {
        Budget budget = Budget.of(MARCH, Currency.ILS, List.of(new CategoryLimit(GROCERIES, ils("1200.00"))));
        budgets.save(budget);

        BudgetRepository elsewhere = new JdbcBudgetRepository(freshConnection());

        assertThat(elsewhere.findById(budget.id())).contains(budget);
    }

    /**
     * {@code numeric} keeps whatever scale it is given, and {@link Money} refuses more precision than a
     * currency admits. Reading such a row is a server fault naming the column, not an {@code
     * IllegalArgumentException} a problem handler would answer as a broken rule.
     */
    @Test
    void Given_a_limit_stored_with_more_precision_than_its_currency_admits_When_read_Then_it_is_a_server_fault() {
        BudgetId id = budgetStoredDirectly();
        jdbc().sql("INSERT INTO budgeting.budget_limit VALUES (:id, 'groceries', 1200.005, 'ILS')")
                .param("id", id.value())
                .update();

        assertThatThrownBy(() -> budgets.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budgeting.budget_limit.amount");
    }

    /**
     * No constraint ties a limit's currency to its budget's, because they sit in two tables. {@code
     * Budget} refuses the pair, and a stored pair it refuses is a server fault naming the budget.
     */
    @Test
    void Given_a_limit_stored_in_another_currency_than_its_budget_When_read_Then_it_is_a_server_fault() {
        BudgetId id = budgetStoredDirectly();
        jdbc().sql("INSERT INTO budgeting.budget_limit VALUES (:id, 'groceries', 1200.00, 'USD')")
                .param("id", id.value())
                .update();

        assertThatThrownBy(() -> budgets.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budgeting.budget")
                .hasMessageContaining(id.value().toString());
    }

    /**
     * {@code Budget} refuses a budget with no limits, and the schema cannot, because the limits sit in a
     * table of their own. A stored budget with none is a server fault naming the budget, not a rule the
     * caller broke.
     */
    @Test
    void Given_a_budget_stored_with_no_limits_When_read_Then_it_is_a_server_fault() {
        BudgetId id = budgetStoredDirectly();

        assertThatThrownBy(() -> budgets.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budgeting.budget")
                .hasMessageContaining(id.value().toString());
    }

    /** An ILS budget for March with no limits yet, written past the aggregate so a test can add a bad one. */
    private BudgetId budgetStoredDirectly() {
        BudgetId id = BudgetId.generate();
        jdbc().sql("INSERT INTO budgeting.budget VALUES (:id, :start, :end, 'ILS')")
                .param("id", id.value())
                .param("start", MARCH.start())
                .param("end", MARCH.end())
                .update();
        return id;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
