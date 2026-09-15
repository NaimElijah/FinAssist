package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import java.util.List;

/** Reads the budgets that have been defined. */
public interface ListBudgetsUseCase {

    /**
     * Every budget, ordered by period start descending then id — the most recent period first, as
     * {@code BudgetRepository.all()} promises.
     */
    List<Budget> all();
}
