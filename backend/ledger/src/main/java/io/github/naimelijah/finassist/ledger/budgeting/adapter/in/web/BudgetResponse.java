package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A defined budget: its period, currency, per-category limits and their total. Every member is
 * always present, and marked required in the contract.
 *
 * <p>{@code limits} is sorted by category name. {@link Budget} holds them in a {@code Map.copyOf},
 * whose iteration order is unspecified, and a contract needs a stable one. {@code total} is {@link
 * Budget#total()}, derived on every call and never stored.
 */
public record BudgetResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BudgetPeriodDto period,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currency currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CategoryLimitDto> limits,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto total) {

    private static final Comparator<Category> BY_CATEGORY_NAME = Comparator.comparing(Category::name);

    static BudgetResponse from(Budget budget) {
        List<CategoryLimitDto> limits = budget.limits().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(BY_CATEGORY_NAME))
                .map(entry -> CategoryLimitDto.from(entry.getKey(), entry.getValue()))
                .toList();
        return new BudgetResponse(
                budget.id().value(),
                BudgetPeriodDto.from(budget.period()),
                budget.currency(),
                limits,
                MoneyDto.from(budget.total()));
    }
}
