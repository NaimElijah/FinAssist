package io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A development seam, not the store. ADR-006 is still Proposed and this adds no driver and no schema;
 * it exists so the application layer is runnable while the store is an open decision.
 */
public class InMemoryBudgetRepository implements BudgetRepository {

    private final Map<BudgetId, Budget> budgets = new ConcurrentHashMap<>();

    @Override
    public Optional<Budget> findById(BudgetId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(budgets.get(id));
    }

    @Override
    public void save(Budget budget) {
        Objects.requireNonNull(budget, "budget");
        budgets.put(budget.id(), budget);
    }
}
