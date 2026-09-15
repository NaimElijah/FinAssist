package io.github.naimelijah.finassist.ledger.budgeting.application.port.in;

import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import java.util.List;

/**
 * Reads the categorisation rules a review matches against.
 *
 * <p>Named {@code rules()} rather than {@code all()}. {@code BudgetService} implements this
 * alongside {@link ListBudgetsUseCase}, whose {@code all()} returns {@code List<Budget>} — two
 * methods named {@code all()} on one class, differing only in an unrelated generic return type,
 * do not compile: Java erases both to the same signature and neither return type is a subtype of
 * the other. This is the one in-port here whose method is not called {@code all()} for that
 * reason.
 */
public interface ListCategorisationRulesUseCase {

    /**
     * Every rule, in the order it was added — {@code CategorisationRuleRepository.all()}'s contract,
     * passed through unchanged. A review takes the first rule that matches a description, so this
     * order is what decides the answer.
     */
    List<CategorisationRule> rules();
}
