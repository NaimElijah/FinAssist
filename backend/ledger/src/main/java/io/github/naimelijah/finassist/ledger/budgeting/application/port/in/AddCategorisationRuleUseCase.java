package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;

/** Teaches budgeting to recognise a category from a description. */
public interface AddCategorisationRuleUseCase {

    /**
     * Adds a rule, after every rule already added.
     *
     * <p>Order matters and is not incidental: a review takes the first rule that matches, so a rule
     * added later never overrides an earlier one that also matches. Nothing here refuses an overlap —
     * whether one is a mistake is not a question this layer can answer, and a rule that shadows
     * another is visible in the review rather than hidden by it.
     */
    void add(CategorisationRule rule);
}
