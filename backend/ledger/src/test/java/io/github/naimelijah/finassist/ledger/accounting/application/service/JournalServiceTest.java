package io.github.naimelijah.finassist.ledger.accounting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.JournalLine;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Reading the books: a balance, and the journal for a period.
 *
 * <p>Nothing here changes anything, which is what makes the interesting questions all be about what
 * is returned. The half-open range and the exclusion of voided transactions are the two things a
 * caller counting spend must be able to rely on without knowing to filter.
 *
 * <p>The store is mocked and hands back exactly what the port promises: results ordered by date then
 * identity. That the real store actually keeps that promise is asserted separately, against
 * PostgreSQL, in {@code JdbcTransactionRepositoryIT} - which is the only place it can honestly be
 * checked, since it is a property of an {@code ORDER BY} and not of this service.
 */
class JournalServiceTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_15 = LocalDate.of(2026, 3, 15);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final JournalService service = new JournalService(accounts, transactions);

    private final Account cash = known("Cash", AccountType.ASSET, Currency.ILS);
    private final Account groceries = known("Groceries", AccountType.EXPENSE, Currency.ILS);

    @Test
    void Given_an_account_nothing_has_touched_When_its_balance_is_read_Then_it_is_zero_in_its_currency() {
        when(transactions.findByAccount(cash.id())).thenReturn(List.of());

        assertThat(service.balanceOf(cash.id()).amount()).isEqualTo(Money.zero(Currency.ILS));
    }

    @Test
    void Given_transactions_against_an_account_When_its_balance_is_read_Then_the_entries_are_summed() {
        List<Transaction> posted =
                List.of(purchase(MARCH_1, "Groceries", "12.50"), purchase(MARCH_15, "Groceries", "7.50"));
        when(transactions.findByAccount(any())).thenReturn(posted);

        assertThat(service.balanceOf(cash.id()).amount()).isEqualTo(ils("-20.00"));
        assertThat(service.balanceOf(groceries.id()).amount()).isEqualTo(ils("20.00"));
    }

    @Test
    void Given_a_voided_transaction_When_the_balance_is_read_Then_it_does_not_count() {
        Transaction voided = purchase(MARCH_1, "Groceries", "12.50").markVoided(MARCH_15, "entered twice");
        when(transactions.findByAccount(cash.id()))
                .thenReturn(List.of(voided, purchase(MARCH_15, "Groceries", "7.50")));

        assertThat(service.balanceOf(cash.id()).amount()).isEqualTo(ils("-7.50"));
    }

    @Test
    void Given_an_unknown_account_When_its_balance_is_read_Then_it_is_refused() {
        AccountId unknown = AccountId.generate();
        when(accounts.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.balanceOf(unknown)).isInstanceOf(AccountNotFoundException.class);
    }

    /**
     * The range is the store's to apply, so the mock is asked for it verbatim: what this pins is that
     * the service passes the bounds through untouched rather than widening or re-filtering them.
     */
    @Test
    void Given_a_period_When_the_journal_is_read_Then_the_bounds_are_passed_to_the_store_unchanged() {
        when(transactions.findBetween(MARCH_1, APRIL_1))
                .thenReturn(List.of(purchase(MARCH_1, "First", "1.00"), purchase(MARCH_15, "Middle", "2.00")));

        assertThat(service.postedBetween(MARCH_1, APRIL_1))
                .extracting(JournalLine::description)
                .containsExactly("First", "First", "Middle", "Middle");
    }

    @Test
    void Given_a_voided_transaction_When_the_journal_is_read_Then_its_lines_are_excluded() {
        Transaction voided = purchase(MARCH_1, "Groceries", "12.50").markVoided(MARCH_15, "entered twice");
        when(transactions.findBetween(MARCH_1, APRIL_1))
                .thenReturn(List.of(voided, purchase(MARCH_15, "Rent", "2000.00")));

        assertThat(service.postedBetween(MARCH_1, APRIL_1))
                .extracting(JournalLine::description)
                .containsOnly("Rent");
    }

    @Test
    void Given_a_posted_entry_When_the_journal_is_read_Then_the_line_carries_its_account_type() {
        Transaction posted = purchase(MARCH_1, "Groceries", "12.50");
        when(transactions.findBetween(MARCH_1, APRIL_1)).thenReturn(List.of(posted));

        assertThat(service.postedBetween(MARCH_1, APRIL_1))
                .contains(new JournalLine(
                        posted.id(), MARCH_1, "Groceries", groceries.id(), AccountType.EXPENSE, ils("12.50")));
    }

    /**
     * The chart of accounts is append-only, so a dangling reference cannot arise through the in-port -
     * and in the store a foreign key refuses one outright. Handed back by the mock to prove the guard
     * exists rather than being assumed: a silent skip here would under-report spend, and nothing
     * downstream could tell.
     */
    @Test
    void Given_a_stored_entry_naming_no_account_When_the_journal_is_read_Then_it_is_refused() {
        AccountId dangling = AccountId.generate();
        when(accounts.findById(dangling)).thenReturn(Optional.empty());
        when(transactions.findBetween(MARCH_1, APRIL_1))
                .thenReturn(List.of(Transaction.record(
                        MARCH_1,
                        "Corrupt",
                        List.of(new Entry(dangling, ils("1.00")), new Entry(cash.id(), ils("-1.00"))))));

        assertThatThrownBy(() -> service.postedBetween(MARCH_1, APRIL_1)).isInstanceOf(AccountNotFoundException.class);
    }

    private Transaction purchase(LocalDate on, String description, String amount) {
        return Transaction.record(
                on,
                description,
                List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount))));
    }

    private Account known(String name, AccountType type, Currency currency) {
        Account account = Account.open(name, type, currency);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        return account;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
