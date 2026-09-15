package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetCommand;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a client supplies to define a budget: a period, a currency and its category limits.
 *
 * <p>"At least one limit", "no duplicate category", "limit positive", "limit in the budget's
 * currency" and "start before end" are left to the domain, which throws {@link
 * IllegalArgumentException} for each of them — answered 422 by the composition fallback rather than
 * duplicated here as a bean-validation constraint.
 */
public record DefineBudgetRequest(
        @NotNull @Valid BudgetPeriodDto period,
        @NotNull Currency currency,
        @NotNull List<@NotNull @Valid CategoryLimitDto> limits) {

    DefineBudgetCommand toCommand() {
        return new DefineBudgetCommand(
                period.toBudgetPeriod(),
                currency,
                limits.stream().map(CategoryLimitDto::toCategoryLimit).toList());
    }
}
