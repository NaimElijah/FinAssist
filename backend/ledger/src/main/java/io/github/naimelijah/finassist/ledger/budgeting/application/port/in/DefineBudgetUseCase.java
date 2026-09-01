package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;

/** Sets spending limits for a period. */
public interface DefineBudgetUseCase {

    /**
     * @return the stored budget, carrying its generated identity
     * @throws IllegalArgumentException from the domain when a category is limited twice, a limit is in
     *     the wrong currency or is not positive, or the budget limits nothing at all
     */
    Budget define(DefineBudgetCommand command);
}
