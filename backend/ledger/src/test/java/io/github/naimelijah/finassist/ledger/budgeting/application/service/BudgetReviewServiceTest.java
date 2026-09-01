package io.github.naimelijah.finassist.ledger.budgeting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence.InMemoryBudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence.InMemoryCategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetNotFoundException;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetReview;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.CategorySpend;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetCommand;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendJournal;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendRecord;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.CategoryLimit;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of reviewing a budget.
 *
 * <p>This is the case the context map has been describing since it was written: budgeting needs a
 * figure that lives in accounting and may name no accounting type to get it. Here the port is driven
 * by a stub, which is the point — the service is complete and testable without anything on the other
 * side of the boundary existing.
 *
 * <p>What these pin hardest is that nothing is quietly dropped. Spend for a category with no limit,
 * and spend matching no rule at all, are each reported separately, because folding either away makes
 * a budget look healthier than it is.
 */
class BudgetReviewServiceTest {

    private static final BudgetPeriod MARCH = BudgetPeriod.of(YearMonth.of(2026, 3));
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);
    private static final Category GROCERIES = Category.of("groceries");
    private static final Category RENT = Category.of("rent");
    private static final Category COFFEE = Category.of("coffee");

    private final InMemoryBudgetRepository budgets = new InMemoryBudgetRepository();
    private final InMemoryCategorisationRuleRepository rules = new InMemoryCategorisationRuleRepository();
    private final StubSpendJournal spend = new StubSpendJournal();
    private final BudgetService budgetService = new BudgetService(budgets, rules);
    private final BudgetReviewService service = new BudgetReviewService(budgets, rules, spend);

    @Test
    void Given_spend_matching_a_rule_When_reviewed_Then_it_is_taken_off_that_category() {
        Budget budget = define(new CategoryLimit(GROCERIES, ils("1200.00")));
        rules.add(new CategorisationRule("superpharm", GROCERIES));
        spend.holds(record("SUPERPHARM TLV", "310.50"));

        BudgetReview review = service.review(budget.id());

        assertThat(review.categories())
                .containsExactly(new CategorySpend(GROCERIES, ils("1200.00"), ils("310.50"), ils("889.50")));
    }

    @Test
    void Given_a_budgeted_category_with_no_spend_When_reviewed_Then_its_whole_limit_remains() {
        Budget budget = define(new CategoryLimit(GROCERIES, ils("1200.00")));

        BudgetReview review = service.review(budget.id());

        assertThat(review.categories())
                .containsExactly(
                        new CategorySpend(GROCERIES, ils("1200.00"), Money.zero(Currency.ILS), ils("1200.00")));
    }

    @Test
    void Given_more_spent_than_allowed_When_reviewed_Then_the_overspend_is_reported_not_refused() {
        Budget budget = define(new CategoryLimit(COFFEE, ils("100.00")));
        rules.add(new CategorisationRule("cafe", COFFEE));
        spend.holds(record("CAFE JOE", "140.00"));

        BudgetReview review = service.review(budget.id());

        assertThat(review.categories()).singleElement().satisfies(category -> {
            assertThat(category.remaining()).isEqualTo(ils("-40.00"));
            assertThat(category.isOverspent()).isTrue();
        });
        assertThat(review.overspent()).hasSize(1);
    }

    @Test
    void Given_spend_for_a_category_with_no_limit_When_reviewed_Then_it_is_reported_as_unbudgeted() {
        Budget budget = define(new CategoryLimit(GROCERIES, ils("1200.00")));
        rules.add(new CategorisationRule("cafe", COFFEE));
        spend.holds(record("CAFE JOE", "18.00"));

        BudgetReview review = service.review(budget.id());

        assertThat(review.unbudgeted()).containsExactly(java.util.Map.entry(COFFEE, ils("18.00")));
        assertThat(review.categories()).extracting(CategorySpend::category).containsExactly(GROCERIES);
    }

    @Test
    void Given_spend_matching_no_rule_When_reviewed_Then_it_is_reported_as_uncategorised() {
        Budget budget = define(new CategoryLimit(GROCERIES, ils("1200.00")));
        rules.add(new CategorisationRule("superpharm", GROCERIES));
        spend.holds(record("SOMETHING NOBODY TAUGHT IT", "42.00"));

        BudgetReview review = service.review(budget.id());

        assertThat(review.uncategorised()).isEqualTo(ils("42.00"));
        assertThat(review.categories())
                .allSatisfy(category -> assertThat(category.spent()).isEqualTo(Money.zero(Currency.ILS)));
    }

    /** Two rules can match one description. The order they were added in decides, and nothing else. */
    @Test
    void Given_two_rules_that_both_match_When_reviewed_Then_the_first_added_wins() {
        Budget budget = define(new CategoryLimit(COFFEE, ils("100.00")), new CategoryLimit(GROCERIES, ils("100.00")));
        rules.add(new CategorisationRule("cafe", COFFEE));
        rules.add(new CategorisationRule("ca", GROCERIES));
        spend.holds(record("CAFE JOE", "18.00"));

        BudgetReview review = service.review(budget.id());

        assertThat(review.categories())
                .filteredOn(category -> category.category().equals(COFFEE))
                .singleElement()
                .extracting(CategorySpend::spent)
                .isEqualTo(ils("18.00"));
    }

    @Test
    void Given_a_refund_in_a_category_When_reviewed_Then_it_reduces_what_was_spent() {
        Budget budget = define(new CategoryLimit(GROCERIES, ils("1200.00")));
        rules.add(new CategorisationRule("superpharm", GROCERIES));
        spend.holds(record("SUPERPHARM TLV", "310.50"), record("SUPERPHARM TLV refund", "-10.50"));

        BudgetReview review = service.review(budget.id());

        assertThat(review.categories())
                .singleElement()
                .extracting(CategorySpend::spent)
                .isEqualTo(ils("300.00"));
    }

    /**
     * {@code Budget} keeps its limits in a {@code Map.copyOf}, whose iteration order is unspecified. A
     * review that followed it would be reproducible only by luck, so the order is imposed here.
     */
    @Test
    void Given_several_budgeted_categories_When_reviewed_Then_they_come_back_ordered_by_name() {
        Budget budget = define(
                new CategoryLimit(RENT, ils("4000.00")),
                new CategoryLimit(GROCERIES, ils("1200.00")),
                new CategoryLimit(COFFEE, ils("100.00")));

        BudgetReview review = service.review(budget.id());

        assertThat(review.categories()).extracting(CategorySpend::category).containsExactly(COFFEE, GROCERIES, RENT);
    }

    @Test
    void Given_a_budget_When_reviewed_Then_the_journal_is_asked_for_its_own_period_and_currency() {
        Budget budget = define(new CategoryLimit(GROCERIES, ils("1200.00")));

        service.review(budget.id());

        assertThat(spend.askedPeriod).isEqualTo(MARCH);
        assertThat(spend.askedCurrency).isEqualTo(Currency.ILS);
    }

    @Test
    void Given_an_unknown_budget_When_reviewed_Then_it_is_refused() {
        assertThatThrownBy(() -> service.review(BudgetId.generate())).isInstanceOf(BudgetNotFoundException.class);
    }

    /**
     * The port promises the currency it was asked for. A translation that broke that promise would
     * understate a category by however much it dropped, and nothing downstream could tell — so it is
     * refused at the boundary rather than absorbed.
     */
    @Test
    void Given_a_journal_answering_in_another_currency_When_reviewed_Then_it_is_refused() {
        Budget budget = define(new CategoryLimit(GROCERIES, ils("1200.00")));
        rules.add(new CategorisationRule("superpharm", GROCERIES));
        spend.holds(new SpendRecord("SUPERPHARM TLV", Money.of("310.50", Currency.USD), MARCH_5));

        assertThatThrownBy(() -> service.review(budget.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SpendJournal returned");
    }

    private Budget define(CategoryLimit... limits) {
        return budgetService.define(new DefineBudgetCommand(MARCH, Currency.ILS, List.of(limits)));
    }

    private static SpendRecord record(String description, String amount) {
        return new SpendRecord(description, ils(amount), MARCH_5);
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    /** Stands in for the translation over accounting, which lives outside both contexts (ADR-022). */
    private static final class StubSpendJournal implements SpendJournal {

        private final List<SpendRecord> records = new ArrayList<>();
        private BudgetPeriod askedPeriod;
        private Currency askedCurrency;

        void holds(SpendRecord... spend) {
            records.addAll(List.of(spend));
        }

        @Override
        public List<SpendRecord> spendDuring(BudgetPeriod period, Currency currency) {
            askedPeriod = period;
            askedCurrency = currency;
            return List.copyOf(records);
        }
    }
}
