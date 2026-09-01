package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.CategoryLimit;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.List;
import java.util.Objects;

/**
 * What a caller must supply to define a budget.
 *
 * <p>No identity — the service generates one. Everything else is passed to {@code Budget.of}, which
 * holds the rules: one limit per category, every limit in the budget's currency, every limit positive,
 * and at least one of them.
 */
public record DefineBudgetCommand(BudgetPeriod period, Currency currency, List<CategoryLimit> limits) {

    public DefineBudgetCommand {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(currency, "currency");
        limits = List.copyOf(limits);
    }
}
