package io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A development seam, not the store. See {@link InMemoryBudgetRepository}.
 *
 * <p>A list rather than a set, because the port makes insertion order part of its contract: a review
 * takes the first rule that matches, so the order decides the answer.
 */
public class InMemoryCategorisationRuleRepository implements CategorisationRuleRepository {

    private final List<CategorisationRule> rules = new CopyOnWriteArrayList<>();

    @Override
    public List<CategorisationRule> all() {
        return List.copyOf(rules);
    }

    @Override
    public void add(CategorisationRule rule) {
        Objects.requireNonNull(rule, "rule");
        rules.add(rule);
    }
}
