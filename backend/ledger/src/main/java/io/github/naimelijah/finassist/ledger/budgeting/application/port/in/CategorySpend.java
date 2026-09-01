package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.Objects;

/**
 * One budgeted category, with what it allowed, what it consumed, and what is left.
 *
 * <p>{@code remaining} is negative when the category is overspent. That is reported rather than
 * refused, exactly as {@code Budget.remaining} reports it — an overspend is a fact about the month,
 * not an invalid state.
 */
public record CategorySpend(Category category, Money limit, Money spent, Money remaining) {

    public CategorySpend {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(spent, "spent");
        Objects.requireNonNull(remaining, "remaining");
    }

    /** True when more was spent than the category allowed. */
    public boolean isOverspent() {
        return remaining.isNegative();
    }
}
