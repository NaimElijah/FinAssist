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
 * <p>Invariants: at least one limit; one limit per category; every limit positive and in the
 * budget's currency. Two limits for the same category are refused rather than merged — merging would
 * guess at an intent the owner never expressed.
 *
 * <p>Limits are held as a map keyed by category, because "one limit per category" is precisely what
 * makes a map the right shape. Validating with a map and then storing a list would throw that
 * invariant away and leave every lookup a linear scan.
 *
 * <p>This aggregate names no accounting type. It holds {@link Category} and {@link Money}, both of
 * which belong to budgeting or to the shared kernel;
 * {@code LedgerArchitectureTest.contexts_do_not_depend_on_each_other} fails the build if an
 * accounting class appears here.
 *
 * <p>The canonical constructor checks every invariant above and takes the identity as given, which
 * is how a stored budget is rebuilt; a new one is built with {@link #of}, which generates the
 * identity. It throws {@link IllegalArgumentException} for an empty {@code limits}, or a limit that
 * is not positive or not in {@code currency}.
 *
 * @param id the budget's identity
 * @param period the span the limits cover
 * @param currency the one currency of every limit
 * @param limits the limit per category, copied on construction; its iteration order is unspecified
 */
public record Budget(BudgetId id, BudgetPeriod period, Currency currency, Map<Category, Money> limits) {

    public Budget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(limits, "limits");

        limits = Map.copyOf(limits);
        if (limits.isEmpty()) {
            throw new IllegalArgumentException("A budget with no limits budgets nothing");
        }
        for (Map.Entry<Category, Money> limit : limits.entrySet()) {
            requireBudgetCurrency(limit.getKey(), limit.getValue(), currency);
            requirePositive(limit.getKey(), limit.getValue());
        }
    }

    /**
     * Builds a new budget with a generated identity, refusing two limits for one category.
     *
     * @throws IllegalArgumentException if a category is limited twice, a limit is in a currency
     *     other than {@code currency}, or {@code limits} is empty
     */
    public static Budget of(BudgetPeriod period, Currency currency, List<CategoryLimit> limits) {
        Objects.requireNonNull(limits, "limits");
        Map<Category, Money> byCategory = new LinkedHashMap<>();
        for (CategoryLimit limit : limits) {
            requireBudgetCurrency(limit.category(), limit.limit(), currency);
            if (byCategory.putIfAbsent(limit.category(), limit.limit()) != null) {
                throw new IllegalArgumentException(
                        "Category " + limit.category() + " has more than one limit in this budget");
            }
        }
        return new Budget(BudgetId.generate(), period, currency, byCategory);
    }

    /** The limit set for a category, or empty when the budget says nothing about it. */
    public Optional<Money> limitFor(Category category) {
        Objects.requireNonNull(category, "category");
        return Optional.ofNullable(limits.get(category));
    }

    /** The total budgeted across every category. */
    public Money total() {
        Money total = Money.zero(currency);
        for (Money limit : limits.values()) {
            total = total.plus(limit);
        }
        return total;
    }

    /**
     * What is left in a category once {@code spent} is taken off its limit.
     *
     * <p>Negative when overspent — that is a fact worth reporting, not an error to reject. Empty when
     * the budget sets no limit for the category at all. A limit of zero is not a possible state,
     * because the constructor refuses one, as {@link CategoryLimit} does.
     *
     * <p>Where the category has a limit, a {@code spent} in another currency than the budget's is
     * refused with the shared kernel's {@code CurrencyMismatchException}, as no rates exist to convert
     * it (ADR-012). Where it has none, the answer is empty whatever the currency of {@code spent}.
     */
    public Optional<Money> remaining(Category category, Money spent) {
        Objects.requireNonNull(spent, "spent");
        return limitFor(category).map(limit -> limit.minus(spent));
    }

    /**
     * Repeats {@link CategoryLimit}'s rule, because the public constructor takes bare amounts and
     * builds no {@code CategoryLimit}.
     */
    private static void requirePositive(Category category, Money limit) {
        if (!limit.isPositive()) {
            throw new IllegalArgumentException("Limit for " + category + " must be positive");
        }
    }

    private static void requireBudgetCurrency(Category category, Money limit, Currency currency) {
        if (limit.currency() != currency) {
            throw new IllegalArgumentException(
                    "Limit for " + category + " is in " + limit.currency() + " but the budget is in " + currency);
        }
    }
}
