package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;

/** Reads how a budget is doing. */
public interface ReviewBudgetUseCase {

    /**
     * Categorises the period's spend and takes it off the limits.
     *
     * <p>Derived on every call and stored nowhere, exactly as a balance is. A budget holds no spend
     * field for the same reason an account holds no balance field.
     *
     * @throws BudgetNotFoundException if no such budget exists
     */
    BudgetReview review(BudgetId budget);
}
