package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;

/**
 * No budget of that identity has been defined.
 *
 * <p>The web adapter answers it as 404 {@code BUDGET_NOT_FOUND}, with the message as the detail.
 */
public class BudgetNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param budget the identity that matched no budget; the message names it
     */
    public BudgetNotFoundException(BudgetId budget) {
        super("No budget with id " + budget);
    }
}
