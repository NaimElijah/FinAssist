package io.github.naimelijah.finassist.ledger.budgeting.application.service;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetNotFoundException;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetReview;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.CategorySpend;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ReviewBudgetUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendJournal;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendRecord;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Answers how a budget is doing, by fetching spend, categorising it, and taking it off the limits.
 *
 * <p>This is the use case the context map has been describing since it was written: budgeting needs a
 * figure that lives in accounting and may not name a single accounting type to get it. It asks
 * {@link SpendJournal}, which it owns and which speaks its own vocabulary, and something outside both
 * contexts satisfies that port (ADR-022).
 *
 * <p>The service holds no rule of its own. Matching is {@code CategorisationRule.matches}, subtraction
 * is {@code Budget.remaining}, and what remains here is the fold between them.
 */
public class BudgetReviewService implements ReviewBudgetUseCase {

    private static final Comparator<Category> BY_NAME = Comparator.comparing(Category::name);

    private final BudgetRepository budgets;
    private final CategorisationRuleRepository rules;
    private final SpendJournal spend;

    public BudgetReviewService(BudgetRepository budgets, CategorisationRuleRepository rules, SpendJournal spend) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.spend = Objects.requireNonNull(spend, "spend");
    }

    /**
     * {@inheritDoc}
     *
     * <p>A spend record matching no rule is counted as uncategorised, and one matching a rule for a
     * category the budget sets no limit for as unbudgeted; the first matching rule decides.
     *
     * @param id the identity of the budget to review
     * @return the review, with spend that fell outside the budget reported rather than dropped
     * @throws BudgetNotFoundException if no such budget exists
     * @throws IllegalStateException if the {@link SpendJournal} returns a record in a currency
     *     other than the budget's, which means the translation behind the port is broken
     */
    @Override
    public BudgetReview review(BudgetId id) {
        Objects.requireNonNull(id, "id");
        Budget budget = budgets.findById(id).orElseThrow(() -> new BudgetNotFoundException(id));

        List<CategorisationRule> ordered = rules.all();
        Map<Category, Money> byCategory = new TreeMap<>(BY_NAME);
        Money uncategorised = Money.zero(budget.currency());

        for (SpendRecord record : spend.spendDuring(budget.period(), budget.currency())) {
            requireBudgetCurrency(record, budget);
            Optional<Category> category = categorise(record.description(), ordered);
            if (category.isEmpty()) {
                uncategorised = uncategorised.plus(record.amount());
                continue;
            }
            byCategory.merge(category.get(), record.amount(), Money::plus);
        }

        List<CategorySpend> categories = new ArrayList<>();
        for (Category budgeted :
                budget.limits().keySet().stream().sorted(BY_NAME).toList()) {
            Money limit = budget.limits().get(budgeted);
            // Removed rather than read, so that whatever survives the loop is by construction the
            // spend that matched a rule but is not budgeted for.
            Money consumed =
                    Optional.ofNullable(byCategory.remove(budgeted)).orElseGet(() -> Money.zero(budget.currency()));
            // Budget.remaining is the published domain operation, and the one the requirements name as
            // enforcing this rule, so it is called rather than open-coded as limit.minus(consumed) -
            // which is exactly what it does today, and which would leave nothing pointing at the rule if
            // it ever grows one. The Optional cannot be empty for a key taken from limits().keySet(), so
            // this branch is a deliberate equivalent mutant; ADR-017 accepts those rather than contorting
            // code to kill them.
            Money remaining = budget.remaining(budgeted, consumed)
                    .orElseThrow(() -> new IllegalStateException(
                            "Budget " + id + " lists a limit for " + budgeted + " that it cannot then find"));
            categories.add(new CategorySpend(budgeted, limit, consumed, remaining));
        }

        return new BudgetReview(
                budget.id(),
                budget.period(),
                budget.currency(),
                categories,
                new LinkedHashMap<>(byCategory),
                uncategorised);
    }

    private static Optional<Category> categorise(String description, List<CategorisationRule> ordered) {
        for (CategorisationRule rule : ordered) {
            if (rule.matches(description)) {
                return Optional.of(rule.category());
            }
        }
        return Optional.empty();
    }

    /**
     * The port promises spend in the budget's currency. Trusting that silently would let a broken
     * translation understate a category by however much it dropped, so it is checked at the boundary
     * and refused loudly rather than absorbed.
     */
    private static void requireBudgetCurrency(SpendRecord record, Budget budget) {
        if (record.amount().currency() != budget.currency()) {
            throw new IllegalStateException(
                    "SpendJournal returned " + record.amount().currency() + " for a budget in " + budget.currency()
                            + "; it was asked for " + budget.currency());
        }
    }
}
