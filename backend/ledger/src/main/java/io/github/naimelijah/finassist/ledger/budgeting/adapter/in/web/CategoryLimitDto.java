package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.CategoryLimit;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One category's spending limit on the wire, used both to define a budget and to report one back
 * (ADR-028).
 *
 * <p>{@code category} travels as the raw string a client typed; {@link Category#of} normalises it
 * (lower-cased) when this is turned into a domain limit, exactly as {@code Category}'s own
 * constructor would.
 */
public record CategoryLimitDto(
        @NotBlank String category, @NotNull @Valid MoneyDto limit) {

    static CategoryLimitDto from(Category category, Money limit) {
        return new CategoryLimitDto(category.name(), MoneyDto.from(limit));
    }

    /**
     * @throws IllegalArgumentException if the limit is not positive, or carries more precision than
     *     its currency admits
     */
    CategoryLimit toCategoryLimit() {
        return new CategoryLimit(Category.of(category), limit.toMoney());
    }
}
