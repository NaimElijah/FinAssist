package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a budget is doing: every budgeted category, and the two kinds of spend that fell outside it.
 *
 * <p><strong>Nothing is silently dropped.</strong> Spend that matched a rule for a category the budget
 * sets no limit for is {@code unbudgeted}; spend that matched no rule at all is {@code uncategorised}.
 * Folding either into a total, or leaving it out, would make a budget look healthier than it is — and
 * the second number is the one that says the rules need work rather than the spending.
 *
 * <p>Both collections are ordered, and {@code BudgetReviewService} builds them ordered by category
 * name rather than by encounter. {@code Budget} keeps its limits in a {@code Map.copyOf}, whose
 * iteration order is unspecified, so a review that followed it would be reproducible only by luck.
 * This record keeps whatever order it is given; it does not sort.
 *
 * <p>No component may be null. The categories are copied, so a later change to the caller's list does
 * not reach the review, and a null category is refused as a null list is; {@code unbudgeted} is
 * copied too, in the order it is given.
 */
public record BudgetReview(
        BudgetId budget,
        BudgetPeriod period,
        Currency currency,
        List<CategorySpend> categories,
        Map<Category, Money> unbudgeted,
        Money uncategorised) {

    public BudgetReview {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(unbudgeted, "unbudgeted");
        Objects.requireNonNull(uncategorised, "uncategorised");
        categories = List.copyOf(categories);
        // Not Map.copyOf: it is unmodifiable and unordered, and the order is the point.
        unbudgeted = Collections.unmodifiableMap(new LinkedHashMap<>(unbudgeted));
    }

    /** Every budgeted category that is over its limit, in the same order. */
    public List<CategorySpend> overspent() {
        return categories.stream().filter(CategorySpend::isOverspent).toList();
    }
}
