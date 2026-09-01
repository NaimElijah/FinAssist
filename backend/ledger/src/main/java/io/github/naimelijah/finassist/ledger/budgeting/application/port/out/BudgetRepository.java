package io.github.naimelijah.finassist.ledger.budgeting.application.port.out;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import java.util.Optional;

/** Where budgets are kept. No delete, for the reason ADR-021 gives about every repository port here. */
public interface BudgetRepository {

    Optional<Budget> findById(BudgetId id);

    void save(Budget budget);
}
