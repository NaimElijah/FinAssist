package io.github.naimelijah.finassist.ledger.budgeting.domain.budget;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.Objects;

/**
 * How much may be spent in one category over a budget period.
 *
 * <p>A limit of zero or less is refused: zero means "do not spend here", which is the absence of a
 * limit rather than a limit of nothing, and a negative limit has no meaning at all.
 */
public record CategoryLimit(Category category, Money limit) {

    public CategoryLimit {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(limit, "limit");
        if (!limit.isPositive()) {
            throw new IllegalArgumentException(
                    "A category limit must be positive but was " + limit + "; omit the category instead");
        }
    }
}
