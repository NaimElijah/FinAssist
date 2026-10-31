package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;

/** No budget of that identity has been defined. */
public class BudgetNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BudgetNotFoundException(BudgetId budget) {
        super("No budget with id " + budget);
    }
}
