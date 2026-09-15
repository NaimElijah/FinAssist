package io.github.naimelijah.finassist.ledger.composition;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.JdbcAccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.JdbcTransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.service.AccountService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.JournalService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.TransactionService;
import io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence.JdbcBudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence.JdbcCategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendJournal;
import io.github.naimelijah.finassist.ledger.budgeting.application.service.BudgetReviewService;
import io.github.naimelijah.finassist.ledger.budgeting.application.service.BudgetService;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.event.InMemoryPlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence.JdbcPlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence.JdbcScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPositionProvider;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.application.service.ForecastService;
import io.github.naimelijah.finassist.ledger.planning.application.service.PlanService;
import io.github.naimelijah.finassist.ledger.planning.application.service.ScenarioService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Assembles the ledger: every adapter, every service, and the two translations between contexts.
 *
 * <p>The one place in this service that knows Spring exists, apart from {@code LedgerApplication}
 * itself. The application layer carries no annotation and is wired here by constructor, which
 * {@code LedgerArchitectureTest.the_application_layer_depends_on_no_framework} now enforces rather
 * than leaving to intent.
 *
 * <p>Every repository bean is backed by PostgreSQL. ADR-006 was ratified on 2026-09-06 and the
 * in-memory adapters it was waiting on were deleted rather than kept behind a profile: a second
 * implementation that satisfies none of the persistence requirement is a code path nothing should
 * be able to select by accident. The SQL is hand-written and the schema is Flyway's (ADR-024).
 *
 * <p>The event ports are the exception, and deliberately so. Nothing subscribes to a domain event
 * yet (ADR-023), and an outbox table with no consumer is exactly what ADR-020 refused to build
 * ahead of one, so those two beans stay in memory until there is something to deliver to.
 *
 * <p>The services are called by the controllers in a context's {@code adapter.in.web}, which are
 * component-scanned rather than built here. That is the one bounded exception ADR-028 makes, so that a
 * {@code @WebMvcTest} slice loads them. What every request passes through first is built here: the Host
 * allow-list that refuses a DNS-rebound page (ADR-027).
 */
@Configuration
public class LedgerConfiguration {

    /** The comma-separated host names the ledger answers to, bound from {@code LEDGER_ALLOWED_HOSTS}. */
    static final String ALLOWED_HOSTS_PROPERTY = "finassist.http.allowed-hosts";

    // ---- web --------------------------------------------------------------------------------

    /**
     * The Host allow-list, registered at the highest filter precedence (ADR-027). A refused request
     * is answered before any filter that traces, logs or dispatches has handled it.
     */
    @Bean
    FilterRegistrationBean<HostAllowListFilter> hostAllowListFilter(
            @Value("${" + ALLOWED_HOSTS_PROPERTY + "}") List<String> allowedHosts) {
        FilterRegistrationBean<HostAllowListFilter> registration =
                new FilterRegistrationBean<>(new HostAllowListFilter(allowedHosts));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Makes a JSON number where the ledger reads text, such as an amount, a malformed body (ADR-028). */
    @Bean
    StrictTextCoercion strictTextCoercion() {
        return new StrictTextCoercion();
    }

    // ---- accounting -------------------------------------------------------------------------

    @Bean
    AccountRepository accountRepository(JdbcClient jdbc) {
        return new JdbcAccountRepository(jdbc);
    }

    @Bean
    TransactionRepository transactionRepository(JdbcClient jdbc) {
        return new JdbcTransactionRepository(jdbc);
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
    BudgetRepository budgetRepository(JdbcClient jdbc) {
        return new JdbcBudgetRepository(jdbc);
    }

    @Bean
    CategorisationRuleRepository categorisationRuleRepository(JdbcClient jdbc) {
        return new JdbcCategorisationRuleRepository(jdbc);
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
    PlannedFlowRepository plannedFlowRepository(JdbcClient jdbc) {
        return new JdbcPlannedFlowRepository(jdbc);
    }

    @Bean
    ScenarioRepository scenarioRepository(JdbcClient jdbc) {
        return new JdbcScenarioRepository(jdbc);
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
