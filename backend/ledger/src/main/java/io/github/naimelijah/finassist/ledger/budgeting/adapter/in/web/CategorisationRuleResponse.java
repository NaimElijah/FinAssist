package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A categorisation rule as the store holds it: its fragment and the category it assigns. Both members
 * are always present, and marked required in the contract.
 *
 * <p>Both values come from the constructed domain rule, already normalised — lower-cased by {@link
 * CategorisationRule} and {@link io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category}
 * — so the client sees what will actually match rather than what it typed.
 */
public record CategorisationRuleResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fragment,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String category) {

    static CategorisationRuleResponse from(CategorisationRule rule) {
        return new CategorisationRuleResponse(rule.fragment(), rule.category().name());
    }
}
