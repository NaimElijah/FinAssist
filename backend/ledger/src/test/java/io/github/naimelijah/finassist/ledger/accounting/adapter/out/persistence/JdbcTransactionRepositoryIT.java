package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What {@code TransactionRepository}'s Javadoc promises, asserted against the store.
 *
 * <p>The ordering claim is the one worth a database. The port makes {@code (occurredOn, id)} part of
 * its contract because a projection and a budget review both fold over these lists, and a fold whose
 * result depends on an order nothing promised is a test that passes until it does not. Against a
 * mock that order is whatever the test handed back; here it is an {@code ORDER BY} or it is nothing.
 */
@SpringBootTest
class JdbcTransactionRepositoryIT extends PostgresFixture {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_15 = LocalDate.of(2026, 3, 15);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    private Account cash;
    private Account groceries;

    @BeforeEach
    void openTheChartOfAccounts() {
        cash = Account.open("Cash", AccountType.ASSET, Currency.ILS);
        groceries = Account.open("Groceries", AccountType.EXPENSE, Currency.ILS);
        accounts.save(cash);
        accounts.save(groceries);
    }

    @Test
    void Given_a_transaction_When_it_is_saved_Then_it_is_read_back_whole() {
        Transaction recorded = record(MARCH_1, "Groceries", "12.50");

        assertThat(transactions.findById(recorded.id())).contains(recorded);
    }

    @Test
    void Given_no_such_transaction_When_it_is_looked_up_Then_nothing_is_found() {
        assertThat(transactions.findById(TransactionId.generate())).isEmpty();
    }

    /** Entry order is part of the aggregate, and an ordinal column is what preserves it. */
    @Test
    void Given_entries_in_an_order_When_read_back_Then_they_come_back_in_it() {
        Transaction split = Transaction.record(
                MARCH_1,
                "Rent, paid in two parts",
                List.of(
                        new Entry(groceries.id(), ils("2000.00")),
                        new Entry(cash.id(), ils("-1500.00")),
                        new Entry(cash.id(), ils("-500.00"))));
        transactions.save(split);

        assertThat(transactions.findById(split.id()).orElseThrow().entries())
                .containsExactlyElementsOf(split.entries());
    }

    @Test
    void Given_transactions_recorded_out_of_order_When_read_by_account_Then_they_come_back_by_date() {
        Transaction later = record(MARCH_15, "Later", "7.50");
        Transaction earlier = record(MARCH_1, "Earlier", "12.50");

        assertThat(transactions.findByAccount(cash.id())).containsExactly(earlier, later);
    }

    @Test
    void Given_transactions_across_a_month_When_read_by_range_Then_the_range_is_half_open() {
        Transaction first = record(MARCH_1, "First", "1.00");
        Transaction middle = record(MARCH_15, "Middle", "2.00");
        record(APRIL_1, "Just past the end", "3.00");

        assertThat(transactions.findBetween(MARCH_1, APRIL_1)).containsExactly(first, middle);
    }

    /**
     * Voided transactions are included by both queries, and the port says so. A balance excludes them
     * by asking {@code countsTowardsBalance}; a store that dropped them would make voiding look like
     * deletion, which is what ADR-020 exists to prevent.
     */
    @Test
    void Given_a_voided_transaction_When_read_back_Then_it_is_still_returned_with_its_reason() {
        Transaction recorded = record(MARCH_1, "Groceries", "12.50");
        Transaction voided = recorded.markVoided(MARCH_15, "entered twice");
        transactions.save(voided);

        assertThat(transactions.findByAccount(cash.id())).containsExactly(voided);
        assertThat(transactions.findById(recorded.id()).orElseThrow().state())
                .isEqualTo(new TransactionState.Voided(MARCH_15, "entered twice"));
    }

    @Test
    void Given_an_account_a_transaction_never_touched_When_read_by_account_Then_it_is_not_returned() {
        Account rent = Account.open("Rent", AccountType.EXPENSE, Currency.ILS);
        accounts.save(rent);
        record(MARCH_1, "Groceries", "12.50");

        assertThat(transactions.findByAccount(rent.id())).isEmpty();
    }

    /**
     * A transaction that touches the account must come back with <em>all</em> its entries, not only
     * the ones that touch it — a half-loaded transaction would not satisfy its own constructor.
     */
    @Test
    void Given_a_transaction_touching_an_account_When_read_by_it_Then_every_entry_is_loaded() {
        Transaction recorded = record(MARCH_1, "Groceries", "12.50");

        assertThat(transactions.findByAccount(groceries.id()).getFirst().entries())
                .hasSize(2)
                .containsExactlyElementsOf(recorded.entries());
    }

    /**
     * The database checks what the constructor checks. ADR-006 asked for constraints that catch a bug
     * in the domain, so this bypasses the aggregate and posts an unbalanced pair directly.
     */
    @Test
    void Given_entries_that_do_not_balance_When_written_directly_Then_the_database_refuses_them() {
        TransactionId id = TransactionId.generate();

        assertThatThrownBy(() -> {
                    jdbc().sql("INSERT INTO accounting.journal_transaction VALUES (:id, :on, 'Wrong', 'ILS')")
                            .param("id", id.value())
                            .param("on", MARCH_1)
                            .update();
                    jdbc().sql("INSERT INTO accounting.journal_entry VALUES (:id, 0, :account, 10.00, 'ILS')")
                            .param("id", id.value())
                            .param("account", groceries.id().value())
                            .update();
                })
                .hasMessageContaining("double entry needs at least two");
    }

    /** Money round-trips through {@code numeric} at the scale its currency admits, and no other. */
    @Test
    void Given_an_amount_in_a_currency_with_no_minor_units_When_read_back_Then_its_scale_survives() {
        Account tokyo = Account.open("Tokyo cash", AccountType.ASSET, Currency.JPY);
        Account travel = Account.open("Travel", AccountType.EXPENSE, Currency.JPY);
        accounts.save(tokyo);
        accounts.save(travel);
        Transaction yen = Transaction.record(
                MARCH_1,
                "Shinkansen",
                List.of(
                        new Entry(travel.id(), Money.of("13870", Currency.JPY)),
                        new Entry(tokyo.id(), Money.of("-13870", Currency.JPY))));

        transactions.save(yen);

        assertThat(transactions.findById(yen.id())).contains(yen);
    }

    /**
     * {@code numeric} keeps whatever scale it is given, and {@link Money} refuses more precision than a
     * currency admits. A row holding such an amount is the store's fault, not the caller's, so reading it
     * must be a server fault naming where the value sits. An {@code IllegalArgumentException} here would
     * reach the problem handler as a broken domain rule and be answered 422, unlogged.
     */
    @Test
    void Given_an_entry_stored_with_more_precision_than_its_currency_admits_When_read_Then_it_is_a_server_fault() {
        TransactionId id = TransactionId.generate();
        jdbc().sql("INSERT INTO accounting.journal_transaction VALUES (:id, :on, 'Too precise', 'ILS')")
                .param("id", id.value())
                .param("on", MARCH_1)
                .update();
        // One statement, so the deferred balance check sees both entries at commit.
        jdbc().sql("""
                        INSERT INTO accounting.journal_entry VALUES
                            (:id, 0, :groceries, 10.005, 'ILS'),
                            (:id, 1, :cash, -10.005, 'ILS')
                        """)
                .param("id", id.value())
                .param("groceries", groceries.id().value())
                .param("cash", cash.id().value())
                .update();
        jdbc().sql("INSERT INTO accounting.journal_transaction_state VALUES (:id, 0, 'PENDING', NULL, NULL)")
                .param("id", id.value())
                .update();

        assertThatThrownBy(() -> transactions.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accounting.journal_entry.amount");
    }

    private Transaction record(LocalDate on, String description, String amount) {
        Transaction recorded = Transaction.record(
                on,
                description,
                List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount))));
        transactions.save(recorded);
        return recorded;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
