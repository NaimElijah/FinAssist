package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;

/** Sets spending limits for a period. */
public interface DefineBudgetUseCase {

    /**
     * Defines a budget from the command and stores it under a newly generated identity.
     *
     * <p>Nothing is stored when the domain refuses the budget. A positive limit is already enforced
     * when each {@code CategoryLimit} is built, so it cannot fail here.
     *
     * @return the stored budget, carrying its generated identity
     * @throws IllegalArgumentException from the domain when a category is limited twice, a limit is in
     *     a currency other than the budget's, or the budget limits nothing at all
     */
    Budget define(DefineBudgetCommand command);
}
