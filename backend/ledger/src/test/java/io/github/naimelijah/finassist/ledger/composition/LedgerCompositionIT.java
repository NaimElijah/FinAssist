package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.service.AccountService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.TransactionService;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetReview;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetCommand;
import io.github.naimelijah.finassist.ledger.budgeting.application.service.BudgetReviewService;
import io.github.naimelijah.finassist.ledger.budgeting.application.service.BudgetService;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.CategoryLimit;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.application.service.ForecastService;
import io.github.naimelijah.finassist.ledger.planning.application.service.ScenarioService;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The wiring, end to end, through the container that will actually run it.
 *
 * <p>Every other test in this layer constructs its services by hand, which is exactly what makes them
 * fast and what proves the layer needs no container. This one is the complement: it asserts that
 * {@link LedgerConfiguration} resolves, and that the two translations really do carry a figure from
 * one context to another once Spring has assembled them. A wiring mistake is invisible to a unit test
 * by construction.
 *
 * <p>Both paths cross a context boundary that no import may cross: budgeting reads spend that
 * accounting recorded, and planning opens a projection from a balance accounting derived.
 *
 * <p>Isolation used to be {@code @DirtiesContext} per method, because the repository beans were
 * in-memory singletons and a shared context let one case see another's accounts, rules and
 * transactions. The state is in PostgreSQL now, so {@link PostgresFixture} empties it between cases
 * and the context is built once. The trap it removed is the same one: the third case somebody adds
 * would otherwise inherit this one's {@code superpharm} rule and pass or fail on JUnit's method
 * order, which is deterministic and deliberately unspecified.
 */
@SpringBootTest
class LedgerCompositionIT extends PostgresFixture {

    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);
    private static final Category GROCERIES = Category.of("groceries");
    private static final Actor RECORDER = new Actor("owner-user");

    @Autowired
    private AccountService accounts;

    @Autowired
    private TransactionService transactions;

    @Autowired
    private BudgetService budgets;

    @Autowired
    private BudgetReviewService reviews;

    @Autowired
    private ScenarioService scenarios;

    @Autowired
    private ForecastService forecasts;

    @Test
    void Given_a_purchase_recorded_in_accounting_When_a_budget_is_reviewed_Then_budgeting_sees_it() {
        Account cash = accounts.open(new OpenAccountCommand("Cash", AccountType.ASSET, Currency.ILS));
        Account groceries = accounts.open(new OpenAccountCommand("Groceries", AccountType.EXPENSE, Currency.ILS));
        transactions.record(new RecordTransactionCommand(
                MARCH_5,
                "SUPERPHARM TLV",
                List.of(new Entry(groceries.id(), ils("310.50")), new Entry(cash.id(), ils("-310.50"))),
                RECORDER));

        budgets.add(new CategorisationRule("superpharm", GROCERIES));
        Budget budget = budgets.define(new DefineBudgetCommand(
                BudgetPeriod.of(YearMonth.of(2026, 3)),
                Currency.ILS,
                List.of(new CategoryLimit(GROCERIES, ils("1200.00")))));

        BudgetReview review = reviews.review(budget.id());

        assertThat(review.categories()).singleElement().satisfies(category -> {
            assertThat(category.spent()).isEqualTo(ils("310.50"));
            assertThat(category.remaining()).isEqualTo(ils("889.50"));
        });
    }

    @Test
    void Given_a_funded_account_When_a_scenario_is_projected_Then_planning_opens_from_its_balance() {
        Account cash = accounts.open(new OpenAccountCommand("Current", AccountType.ASSET, Currency.ILS));
        Account equity = accounts.open(new OpenAccountCommand("Opening", AccountType.EQUITY, Currency.ILS));
        transactions.record(new RecordTransactionCommand(
                LocalDate.of(2026, 1, 1),
                "Opening balance",
                List.of(new Entry(cash.id(), ils("5000.00")), new Entry(equity.id(), ils("-5000.00"))),
                RECORDER));

        Scenario baseline = scenarios.define(
                new DefineScenarioCommand("Do nothing", Currency.ILS, List.of(), GrowthAssumption.none()));

        Projection projection = forecasts.project(new ProjectScenarioCommand(
                baseline.id(),
                new Horizon(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)),
                Cadence.MONTHLY,
                List.of(AccountRef.of(cash.id().toString())),
                List.of()));

        assertThat(projection.endState().cash()).isEqualTo(ils("5000.00"));
        assertThat(projection.firstShortfall()).isEmpty();
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
