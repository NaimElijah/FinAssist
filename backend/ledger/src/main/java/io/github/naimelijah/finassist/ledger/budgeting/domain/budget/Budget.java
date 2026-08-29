package io.github.naimelijah.finassist.ledger.budgeting.domain.budget;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The budgeting aggregate root: what may be spent, per category, over one period.
 *
 * <p><strong>Nothing here records what was spent.</strong> Spend is derived by the caller from the
 * accounting entries falling inside the period, and passed to {@link #remaining} when a figure is
 * wanted. A stored spend total would be a copy of the ledger that drifts from it, which is the same
 * mistake as a stored account balance.
 *
 * <p>Invariants: at least one limit; one limit per category; every limit in the budget's currency.
 * Two limits for the same category are refused rather than merged — merging would guess at an intent
 * the owner never expressed.
 *
 * <p>This aggregate names no accounting type. It holds {@link Category} and {@link Money}, both of
 * which belong to budgeting or to the shared kernel;
 * {@code LedgerArchitectureTest.contexts_do_not_depend_on_each_other} fails the build if an
 * accounting class appears here.
 */
public record Budget(BudgetId id, BudgetPeriod period, Currency currency, List<CategoryLimit> limits) {

    public Budget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(limits, "limits");

        limits = List.copyOf(limits);
        if (limits.isEmpty()) {
            throw new IllegalArgumentException("A budget with no limits budgets nothing");
        }

        Map<Category, CategoryLimit> seen = new LinkedHashMap<>();
        for (CategoryLimit limit : limits) {
            if (limit.limit().currency() != currency) {
                throw new IllegalArgumentException("Limit for "
                        + limit.category()
                        + " is in "
                        + limit.limit().currency()
                        + " but the budget is in "
                        + currency);
            }
            if (seen.putIfAbsent(limit.category(), limit) != null) {
                throw new IllegalArgumentException(
                        "Category " + limit.category() + " has more than one limit in this budget");
            }
        }
    }

    public static Budget of(BudgetPeriod period, Currency currency, List<CategoryLimit> limits) {
        return new Budget(BudgetId.generate(), period, currency, limits);
    }

    /** The limit set for a category, or empty when the budget says nothing about it. */
    public Optional<Money> limitFor(Category category) {
        Objects.requireNonNull(category, "category");
        return limits.stream()
                .filter(limit -> limit.category().equals(category))
                .map(CategoryLimit::limit)
                .findFirst();
    }

    /** The total budgeted across every category. */
    public Money total() {
        Money total = Money.zero(currency);
        for (CategoryLimit limit : limits) {
            total = total.plus(limit.limit());
        }
        return total;
    }

    /**
     * What is left in a category once {@code spent} is taken off its limit.
     *
     * <p>Negative when overspent — that is a fact worth reporting, not an error to reject. Empty when
     * the budget sets no limit for the category, which is different from a limit of zero.
     */
    public Optional<Money> remaining(Category category, Money spent) {
        Objects.requireNonNull(spent, "spent");
        return limitFor(category).map(limit -> limit.minus(spent));
    }
}
