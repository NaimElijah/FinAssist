package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import jakarta.validation.constraints.NotBlank;

/**
 * What a client supplies to teach budgeting a new categorisation rule: a fragment to match on and the
 * category to assign. It has no id, because a rule is never looked up by one.
 */
public record AddCategorisationRuleRequest(
        @NotBlank String fragment, @NotBlank String category) {

    CategorisationRule toRule() {
        return new CategorisationRule(fragment, Category.of(category));
    }
}
