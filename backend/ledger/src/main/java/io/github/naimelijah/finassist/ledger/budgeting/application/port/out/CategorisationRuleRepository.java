package io.github.naimelijah.finassist.ledger.budgeting.application.port.out;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import java.util.List;

/**
 * Where categorisation rules are kept.
 *
 * <p><strong>Order is part of the contract.</strong> A description can match several rules, and
 * {@code BudgetReviewService} takes the first — so "coffee" placed before "cafe" categorises
 * differently from the other way round. Returning them in the order they were added is what makes a
 * review reproducible; a set would make it depend on a hash.
 */
public interface CategorisationRuleRepository {

    /** Every rule, in the order it was added. */
    List<CategorisationRule> all();

    /**
     * Appends a rule after every rule already stored. An overlap with an earlier rule is not
     * refused.
     */
    void add(CategorisationRule rule);
}
