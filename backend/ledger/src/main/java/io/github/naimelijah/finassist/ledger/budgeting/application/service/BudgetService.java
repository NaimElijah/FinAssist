package io.github.naimelijah.finassist.ledger.budgeting.application.service;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.AddCategorisationRuleUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetCommand;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ListBudgetsUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ListCategorisationRulesUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import java.util.List;
import java.util.Objects;

/**
 * Defines budgets and collects categorisation rules.
 *
 * <p>Thin on purpose. Every rule about what a budget may be lives in {@code Budget.of} and
 * {@code CategoryLimit}, and this adds none of its own — a service that started deciding whether a
 * limit was reasonable would be an invariant that had escaped the domain.
 *
 * <p>Budgeting raises no events. It has no lifecycle to announce: a budget is defined and then read,
 * where a transaction and a planned flow both move through sealed states. Giving it events for
 * symmetry would be inventing a lifecycle to justify a package (ADR-023).
 */
public class BudgetService
        implements DefineBudgetUseCase,
                AddCategorisationRuleUseCase,
                ListBudgetsUseCase,
                ListCategorisationRulesUseCase {

    private final BudgetRepository budgets;
    private final CategorisationRuleRepository rules;

    public BudgetService(BudgetRepository budgets, CategorisationRuleRepository rules) {
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    public Budget define(DefineBudgetCommand command) {
        Objects.requireNonNull(command, "command");
        Budget budget = Budget.of(command.period(), command.currency(), command.limits());
        budgets.save(budget);
        return budget;
    }

    @Override
    public void add(CategorisationRule rule) {
        Objects.requireNonNull(rule, "rule");
        rules.add(rule);
    }

    @Override
    public List<Budget> all() {
        return budgets.all();
    }

    @Override
    public List<CategorisationRule> rules() {
        return rules.all();
    }
}
