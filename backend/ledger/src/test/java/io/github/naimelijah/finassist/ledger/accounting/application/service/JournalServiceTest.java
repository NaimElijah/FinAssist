package io.github.naimelijah.finassist.ledger.accounting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryAccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryTransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.JournalLine;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of reading the books.
 *
 * <p>Both answers are derived on every call, so these cases are as much about what is *not* stored as
 * about what is returned. The half-open range and the exclusion of voided transactions are the two
 * things a caller counting spend must be able to rely on without knowing to filter.
 */
class JournalServiceTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_15 = LocalDate.of(2026, 3, 15);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
    private final InMemoryAccountingEvents events = new InMemoryAccountingEvents();
    private final AccountService accountService = new AccountService(accounts, events);
    private final TransactionService transactionService = new TransactionService(accounts, transactions, events);
    private final JournalService service = new JournalService(accounts, transactions);

    private final Account cash = open("Cash", AccountType.ASSET, Currency.ILS);
    private final Account groceries = open("Groceries", AccountType.EXPENSE, Currency.ILS);

    @Test
    void Given_an_account_nothing_has_touched_When_its_balance_is_read_Then_it_is_zero_in_its_currency() {
        assertThat(service.balanceOf(cash.id()).amount()).isEqualTo(Money.zero(Currency.ILS));
    }

    @Test
    void Given_transactions_against_an_account_When_its_balance_is_read_Then_the_entries_are_summed() {
        record(MARCH_1, "Groceries", "12.50");
        record(MARCH_15, "Groceries", "7.50");

        assertThat(service.balanceOf(cash.id()).amount()).isEqualTo(ils("-20.00"));
        assertThat(service.balanceOf(groceries.id()).amount()).isEqualTo(ils("20.00"));
    }

    @Test
    void Given_a_voided_transaction_When_the_balance_is_read_Then_it_does_not_count() {
        Transaction first = record(MARCH_1, "Groceries", "12.50");
        record(MARCH_15, "Groceries", "7.50");

        transactionService.markVoided(first.id(), MARCH_15, "entered twice");

        assertThat(service.balanceOf(cash.id()).amount()).isEqualTo(ils("-7.50"));
    }

    @Test
    void Given_an_unknown_account_When_its_balance_is_read_Then_it_is_refused() {
        assertThatThrownBy(() -> service.balanceOf(AccountId.generate())).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void Given_transactions_across_a_month_When_the_journal_is_read_Then_the_range_is_half_open() {
        record(MARCH_1, "First", "1.00");
        record(MARCH_15, "Middle", "2.00");
        record(APRIL_1, "Just past the end", "3.00");

        List<JournalLine> lines = service.postedBetween(MARCH_1, APRIL_1);

        assertThat(lines).extracting(JournalLine::description).containsExactly("First", "First", "Middle", "Middle");
    }

    @Test
    void Given_a_voided_transaction_When_the_journal_is_read_Then_its_lines_are_excluded() {
        Transaction first = record(MARCH_1, "Groceries", "12.50");
        record(MARCH_15, "Rent", "2000.00");
        transactionService.markVoided(first.id(), MARCH_15, "entered twice");

        assertThat(service.postedBetween(MARCH_1, APRIL_1))
                .extracting(JournalLine::description)
                .containsOnly("Rent");
    }

    @Test
    void Given_a_posted_entry_When_the_journal_is_read_Then_the_line_carries_its_account_type() {
        record(MARCH_1, "Groceries", "12.50");

        assertThat(service.postedBetween(MARCH_1, APRIL_1))
                .contains(new JournalLine(
                        transactions.findByAccount(cash.id()).getFirst().id(),
                        MARCH_1,
                        "Groceries",
                        groceries.id(),
                        AccountType.EXPENSE,
                        ils("12.50")));
    }

    /**
     * The chart of accounts is append-only, so a dangling reference cannot arise through the in-port.
     * Planted directly in the store to prove the guard exists rather than being assumed: a silent skip
     * here would under-report spend, and nothing downstream could tell.
     */
    @Test
    void Given_a_stored_entry_naming_no_account_When_the_journal_is_read_Then_it_is_refused() {
        AccountId dangling = AccountId.generate();
        transactions.save(Transaction.record(
                MARCH_1, "Corrupt", List.of(new Entry(dangling, ils("1.00")), new Entry(cash.id(), ils("-1.00")))));

        assertThatThrownBy(() -> service.postedBetween(MARCH_1, APRIL_1)).isInstanceOf(AccountNotFoundException.class);
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
