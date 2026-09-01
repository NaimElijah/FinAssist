package io.github.naimelijah.finassist.ledger.composition;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryAccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryTransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.service.AccountService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.JournalService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.TransactionService;
import io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence.InMemoryBudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence.InMemoryCategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendJournal;
import io.github.naimelijah.finassist.ledger.budgeting.application.service.BudgetReviewService;
import io.github.naimelijah.finassist.ledger.budgeting.application.service.BudgetService;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.event.InMemoryPlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence.InMemoryPlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence.InMemoryScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPositionProvider;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.application.service.ForecastService;
import io.github.naimelijah.finassist.ledger.planning.application.service.PlanService;
import io.github.naimelijah.finassist.ledger.planning.application.service.ScenarioService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the ledger: every adapter, every service, and the two translations between contexts.
 *
 * <p>The one place in this service that knows Spring exists, apart from {@code LedgerApplication}
 * itself. The application layer carries no annotation and is wired here by constructor, which
 * {@code LedgerArchitectureTest.the_application_layer_depends_on_no_framework} now enforces rather
 * than leaving to intent.
 *
 * <p>Every repository bean is an in-memory adapter, and none of them is the store. ADR-006 is still
 * Proposed; there is no driver on the classpath, no schema and no SQL. What this buys is a service
 * that assembles and runs while the store is an open decision — the beans are replaced when it is
 * settled, not grown into it.
 *
 * <p>Nothing calls any of this yet. There is no inbound adapter, because ADR-004 is Proposed too and
 * the first endpoint is what would decide it.
 */
@Configuration
public class LedgerConfiguration {

    // ---- accounting -------------------------------------------------------------------------

    @Bean
    AccountRepository accountRepository() {
        return new InMemoryAccountRepository();
    }

    @Bean
    TransactionRepository transactionRepository() {
        return new InMemoryTransactionRepository();
    }

    @Bean
    AccountingEvents accountingEvents() {
        return new InMemoryAccountingEvents();
    }

    @Bean
    AccountService accountService(AccountRepository accounts, AccountingEvents events) {
        return new AccountService(accounts, events);
    }

    @Bean
    TransactionService transactionService(
            AccountRepository accounts, TransactionRepository transactions, AccountingEvents events) {
        return new TransactionService(accounts, transactions, events);
    }

    @Bean
    JournalService journalService(AccountRepository accounts, TransactionRepository transactions) {
        return new JournalService(accounts, transactions);
    }

    // ---- budgeting --------------------------------------------------------------------------

    @Bean
    BudgetRepository budgetRepository() {
        return new InMemoryBudgetRepository();
    }

    @Bean
    CategorisationRuleRepository categorisationRuleRepository() {
        return new InMemoryCategorisationRuleRepository();
    }

    @Bean
    BudgetService budgetService(BudgetRepository budgets, CategorisationRuleRepository rules) {
        return new BudgetService(budgets, rules);
    }

    /** The translation that lets budgeting ask accounting a question without naming it. */
    @Bean
    SpendJournal spendJournal(JournalService journal) {
        return new AccountingSpendJournal(journal);
    }

    @Bean
    BudgetReviewService budgetReviewService(
            BudgetRepository budgets, CategorisationRuleRepository rules, SpendJournal spend) {
        return new BudgetReviewService(budgets, rules, spend);
    }

    // ---- planning ---------------------------------------------------------------------------

    @Bean
    PlannedFlowRepository plannedFlowRepository() {
        return new InMemoryPlannedFlowRepository();
    }

    @Bean
    ScenarioRepository scenarioRepository() {
        return new InMemoryScenarioRepository();
    }

    @Bean
    PlanningEvents planningEvents() {
        return new InMemoryPlanningEvents();
    }

    @Bean
    PlanService planService(PlannedFlowRepository flows, PlanningEvents events) {
        return new PlanService(flows, events);
    }

    @Bean
    ScenarioService scenarioService(ScenarioRepository scenarios, PlannedFlowRepository flows, PlanningEvents events) {
        return new ScenarioService(scenarios, flows, events);
    }

    /** The translation that lets planning open a projection from balances it may not read directly. */
    @Bean
    OpeningPositionProvider openingPositionProvider(JournalService journal) {
        return new AccountingOpeningPositions(journal);
    }

    @Bean
    ForecastService forecastService(
            ScenarioRepository scenarios, PlannedFlowRepository flows, OpeningPositionProvider openings) {
        return new ForecastService(scenarios, flows, openings);
    }
}
