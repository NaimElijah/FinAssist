package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryAccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryTransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.service.AccountService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.JournalService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.TransactionService;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendRecord;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of the translation that gives budgeting its spend.
 *
 * <p>ADR-022 records that the composition root holds real mapping code rather than only {@code @Bean}
 * methods, so it has its own tests. The case that matters most is the second one: every purchase has
 * two entries, and counting both would double every figure a budget reports.
 */
class AccountingSpendJournalTest {

    private static final BudgetPeriod MARCH = BudgetPeriod.of(YearMonth.of(2026, 3));
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
    private final InMemoryAccountingEvents events = new InMemoryAccountingEvents();
    private final AccountService accountService = new AccountService(accounts, events);
    private final TransactionService transactionService = new TransactionService(accounts, transactions, events);
    private final AccountingSpendJournal journal =
            new AccountingSpendJournal(new JournalService(accounts, transactions));

    private final Account cash = open("Cash", AccountType.ASSET, Currency.ILS);
    private final Account groceries = open("Groceries", AccountType.EXPENSE, Currency.ILS);

    @Test
    void Given_a_purchase_When_spend_is_asked_for_Then_the_expense_line_becomes_a_spend_record() {
        record(MARCH_5, "SUPERPHARM TLV", "310.50");

        assertThat(journal.spendDuring(MARCH, Currency.ILS))
                .containsExactly(new SpendRecord("SUPERPHARM TLV", ils("310.50"), MARCH_5));
    }

    /**
     * The reason the {@code EXPENSE} filter exists. Every purchase has two entries that sum to zero,
     * so counting both would report spend of zero, and counting them without regard to type would
     * report it twice. Neither is a rounding error.
     */
    @Test
    void Given_a_purchase_When_spend_is_asked_for_Then_the_cash_side_is_not_counted() {
        record(MARCH_5, "SUPERPHARM TLV", "310.50");

        assertThat(journal.spendDuring(MARCH, Currency.ILS)).hasSize(1);
        assertThat(journal.spendDuring(MARCH, Currency.ILS))
                .extracting(SpendRecord::amount)
                .containsExactly(ils("310.50"));
    }

    @Test
    void Given_spend_in_another_currency_When_asked_for_shekels_Then_it_is_excluded() {
        Account dollars = open("US savings", AccountType.ASSET, Currency.USD);
        Account travel = open("Travel", AccountType.EXPENSE, Currency.USD);
        transactionService.record(new RecordTransactionCommand(
                MARCH_5,
                "HOTEL",
                List.of(
                        new Entry(travel.id(), Money.of("120.00", Currency.USD)),
                        new Entry(dollars.id(), Money.of("-120.00", Currency.USD)))));
        record(MARCH_5, "SUPERPHARM TLV", "310.50");

        assertThat(journal.spendDuring(MARCH, Currency.ILS))
                .extracting(SpendRecord::description)
                .containsExactly("SUPERPHARM TLV");
    }

    @Test
    void Given_a_voided_purchase_When_spend_is_asked_for_Then_it_is_excluded() {
        Transaction mistake = record(MARCH_5, "SUPERPHARM TLV", "310.50");
        transactionService.markVoided(mistake.id(), MARCH_5, "entered twice");

        assertThat(journal.spendDuring(MARCH, Currency.ILS)).isEmpty();
    }

    @Test
    void Given_spend_just_past_the_period_When_asked_for_Then_the_range_is_half_open() {
        record(APRIL_1, "APRIL RENT", "2000.00");

        assertThat(journal.spendDuring(MARCH, Currency.ILS)).isEmpty();
    }

    private Transaction record(LocalDate on, String description, String amount) {
        return transactionService.record(new RecordTransactionCommand(
                on,
                description,
                List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount)))));
    }

    private Account open(String name, AccountType type, Currency currency) {
        return accountService.open(new OpenAccountCommand(name, type, currency));
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
