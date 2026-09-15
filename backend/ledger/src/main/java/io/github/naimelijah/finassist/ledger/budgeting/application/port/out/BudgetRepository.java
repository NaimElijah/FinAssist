package io.github.naimelijah.finassist.ledger.budgeting.application.port.out;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import java.util.List;
import java.util.Optional;

/** Where budgets are kept. No delete, for the reason ADR-021 gives about every repository port here. */
public interface BudgetRepository {

    Optional<Budget> findById(BudgetId id);

    /**
     * Every budget, ordered by period start descending then id — {@code ORDER BY period_start DESC,
     * id} — so the most recent period leads a listing, with id as the tiebreaker for two budgets
     * sharing a period start.
     */
    List<Budget> all();

    void save(Budget budget);
}
