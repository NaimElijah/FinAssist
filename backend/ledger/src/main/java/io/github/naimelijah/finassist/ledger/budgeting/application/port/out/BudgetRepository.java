package io.github.naimelijah.finassist.ledger.budgeting.application.port.out;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import java.util.List;
import java.util.Optional;

/** Where budgets are kept. No delete, for the reason ADR-021 gives about every repository port here. */
public interface BudgetRepository {

    /** The budget stored under {@code id}, or empty when none is. */
    Optional<Budget> findById(BudgetId id);

    /**
     * Every budget, ordered by period start descending then id — {@code ORDER BY period_start DESC,
     * id} — so the most recent period leads a listing, with id as the tiebreaker for two budgets
     * sharing a period start.
     */
    List<Budget> all();

    /**
     * Stores the budget under its identity, replacing a budget already stored under it, limits
     * included: a limit the new state no longer holds is gone afterwards.
     */
    void save(Budget budget);
}
