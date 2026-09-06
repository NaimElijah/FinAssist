package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.JournalLine;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewJournalUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendRecord;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The translation that gives budgeting its spend without either context naming the other.
 *
 * <p>It is the only thing between two bounded contexts, and it has real filtering in it rather than
 * delegating methods, so it has its own tests. The case that matters most is the second one: every
 * purchase has two entries, and counting both would double every figure a budget reports.
 *
 * <p>Driven against a mocked {@code ViewJournalUseCase}, which is what the translation actually takes
 * - it never sees a repository, by design (ADR-022). What the journal itself excludes, and how it
 * reads a half-open range, belongs to {@code JournalServiceTest}; asserting it again here would only
 * be asserting the mock.
 */
class AccountingSpendJournalTest {

    private static final BudgetPeriod MARCH = BudgetPeriod.of(YearMonth.of(2026, 3));
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);

    private static final AccountId GROCERIES = AccountId.generate();
    private static final AccountId CASH = AccountId.generate();
    private static final AccountId TRAVEL = AccountId.generate();

    private final ViewJournalUseCase journal = mock(ViewJournalUseCase.class);
    private final AccountingSpendJournal spend = new AccountingSpendJournal(journal);

    @Test
    void Given_a_purchase_When_spend_is_asked_for_Then_the_expense_line_becomes_a_spend_record() {
        when(journal.postedBetween(MARCH.start(), MARCH.end())).thenReturn(purchase("SUPERPHARM TLV", "310.50"));

        assertThat(spend.spendDuring(MARCH, Currency.ILS))
                .containsExactly(new SpendRecord("SUPERPHARM TLV", ils("310.50"), MARCH_5));
    }

    /**
     * The reason the {@code EXPENSE} filter exists. Every purchase has two entries that sum to zero,
     * so counting both would report spend of zero, and counting them without regard to type would
     * report it twice. Neither is a rounding error.
     */
    @Test
    void Given_a_purchase_When_spend_is_asked_for_Then_the_cash_side_is_not_counted() {
        when(journal.postedBetween(MARCH.start(), MARCH.end())).thenReturn(purchase("SUPERPHARM TLV", "310.50"));

        assertThat(spend.spendDuring(MARCH, Currency.ILS))
                .extracting(SpendRecord::amount)
                .containsExactly(ils("310.50"));
    }

    @Test
    void Given_spend_in_another_currency_When_asked_for_shekels_Then_it_is_excluded() {
        JournalLine hotel = new JournalLine(
                TransactionId.generate(),
                MARCH_5,
                "HOTEL",
                TRAVEL,
                AccountType.EXPENSE,
                Money.of("120.00", Currency.USD));
        List<JournalLine> lines = java.util.stream.Stream.concat(
                        purchase("SUPERPHARM TLV", "310.50").stream(), java.util.stream.Stream.of(hotel))
                .toList();
        when(journal.postedBetween(MARCH.start(), MARCH.end())).thenReturn(lines);

        assertThat(spend.spendDuring(MARCH, Currency.ILS))
                .extracting(SpendRecord::description)
                .containsExactly("SUPERPHARM TLV");
    }

    /**
     * The period is budgeting's, the bounds are accounting's, and the translation is where one
     * becomes the other. Passing a widened or shifted range would silently change every figure in a
     * review, so what is asserted is the call, not only the answer.
     */
    @Test
    void Given_a_budget_period_When_spend_is_asked_for_Then_its_half_open_bounds_are_passed_through() {
        spend.spendDuring(MARCH, Currency.ILS);

        verify(journal).postedBetween(MARCH.start(), MARCH.end());
    }

    private static List<JournalLine> purchase(String description, String amount) {
        TransactionId id = TransactionId.generate();
        return List.of(
                new JournalLine(id, MARCH_5, description, GROCERIES, AccountType.EXPENSE, ils(amount)),
                new JournalLine(id, MARCH_5, description, CASH, AccountType.ASSET, ils("-" + amount)));
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
