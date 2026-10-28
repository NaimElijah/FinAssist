package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    /** PostgreSQL compares {@code uuid} byte by byte, so these two order the way they read. */
    private static final UUID SMALLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID LARGER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Actor RECORDER = new Actor("owner-user");

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
        transactions.save(split, RECORDER);

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
        transactions.save(voided, RECORDER);

        assertThat(transactions.findByAccount(cash.id())).containsExactly(voided);
        assertThat(transactions.findById(recorded.id()).orElseThrow().state())
                .isEqualTo(new TransactionState.Voided(MARCH_15, "entered twice"));
    }

    /**
     * {@code journal_transaction.recorded_by} is written once, at insert; each {@code
     * journal_transaction_state} row keeps naming whoever wrote it, so clearing under a different
     * subject must not overwrite the transaction row's own recorder (ADR-033).
     */
    @Test
    void Given_a_transaction_recorded_by_one_subject_When_cleared_by_another_Then_each_row_stores_its_own_writer() {
        Actor recorder = new Actor("owner-user");
        Actor clearer = new Actor("viewer-user");
        Transaction recorded = Transaction.record(
                MARCH_1,
                "Groceries",
                List.of(new Entry(groceries.id(), ils("12.50")), new Entry(cash.id(), ils("-12.50"))));
        transactions.save(recorded, recorder);

        transactions.save(recorded.clear(MARCH_15), clearer);

        assertThat(jdbc().sql("SELECT recorded_by FROM accounting.journal_transaction WHERE id = :id")
                        .param("id", recorded.id().value())
                        .query(String.class)
                        .single())
                .isEqualTo("owner-user");
        assertThat(jdbc().sql("SELECT state, recorded_by FROM accounting.journal_transaction_state"
                                + " WHERE transaction_id = :id ORDER BY sequence")
                        .param("id", recorded.id().value())
                        .query((row, rowNumber) -> row.getString("state") + ":" + row.getString("recorded_by"))
                        .list())
                .containsExactly("PENDING:owner-user", "CLEARED:viewer-user");
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
                    jdbc().sql("INSERT INTO accounting.journal_transaction"
                                    + " (id, occurred_on, description, currency, recorded_by)"
                                    + " VALUES (:id, :on, 'Wrong', 'ILS', :recordedBy)")
                            .param("id", id.value())
                            .param("on", MARCH_1)
                            .param("recordedBy", RECORDER.subject())
                            .update();
                    jdbc().sql("INSERT INTO accounting.journal_entry VALUES (:id, 0, :account, 10.00, 'ILS')")
                            .param("id", id.value())
                            .param("account", groceries.id().value())
                            .update();
                })
                .hasMessageContaining("double entry needs at least two");
    }

    /**
     * The trigger's other three refusals, each reached the same way: a header and two entries written
     * directly, the entries in one statement so the deferred check sees both. Each case trips exactly
     * one branch — the mixed pair sums to zero and the unbalanced pair shares a currency — so a branch
     * dropped from {@code refuse_unbalanced_transaction} fails its own case rather than being masked
     * by a neighbour.
     */
    @ParameterizedTest(name = "{5}")
    @CsvSource({
        "ILS, 10.00, ILS, -10.00, USD, mixes 2 currencies",
        "ILS, 10.00, ILS, -9.00, ILS, does not balance: its entries sum to 1.00",
        "USD, 10.00, USD, -10.00, USD, posts 2 entries in a currency their account does not hold"
    })
    void Given_entries_the_trigger_refuses_When_written_directly_Then_the_database_refuses_them(
            String header,
            String groceriesAmount,
            String groceriesCurrency,
            String cashAmount,
            String cashCurrency,
            String refusal) {
        TransactionId id = TransactionId.generate();
        jdbc().sql("INSERT INTO accounting.journal_transaction"
                        + " (id, occurred_on, description, currency, recorded_by)"
                        + " VALUES (:id, :on, 'Wrong', :currency, :recordedBy)")
                .param("id", id.value())
                .param("on", MARCH_1)
                .param("currency", header)
                .param("recordedBy", RECORDER.subject())
                .update();

        assertThatThrownBy(() -> jdbc().sql("""
                        INSERT INTO accounting.journal_entry VALUES
                            (:id, 0, :groceries, CAST(:groceriesAmount AS numeric), :groceriesCurrency),
                            (:id, 1, :cash, CAST(:cashAmount AS numeric), :cashCurrency)
                        """)
                        .param("id", id.value())
                        .param("groceries", groceries.id().value())
                        .param("groceriesAmount", groceriesAmount)
                        .param("groceriesCurrency", groceriesCurrency)
                        .param("cash", cash.id().value())
                        .param("cashAmount", cashAmount)
                        .param("cashCurrency", cashCurrency)
                        .update())
                .hasMessageContaining("transaction " + id.value())
                .hasMessageContaining(refusal);
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

        transactions.save(yen, RECORDER);

        assertThat(transactions.findById(yen.id())).contains(yen);
    }

    /**
     * {@code numeric} keeps whatever scale it is given, and {@link Money} refuses more precision than a
     * currency admits. A row holding such an amount is the store's fault, not the caller's, so reading it
     * must be a server fault naming where the value sits. An {@code IllegalArgumentException} here would
     * reach the problem handler as a broken domain rule and be answered 422, unlogged.
     *
     * <p>The fault is logged at ERROR, and ECS prints every cause's message into {@code stack_trace}, so it
     * names the row by its key and carries neither the amount nor {@code Money}'s refusal, which repeats it.
     */
    @Test
    void Given_an_entry_stored_with_more_precision_than_its_currency_admits_When_read_Then_it_is_a_server_fault() {
        TransactionId id = TransactionId.generate();
        jdbc().sql("INSERT INTO accounting.journal_transaction"
                        + " (id, occurred_on, description, currency, recorded_by)"
                        + " VALUES (:id, :on, 'Too precise', 'ILS', :recordedBy)")
                .param("id", id.value())
                .param("on", MARCH_1)
                .param("recordedBy", RECORDER.subject())
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
        jdbc().sql("INSERT INTO accounting.journal_transaction_state"
                        + " (transaction_id, sequence, state, effective_on, reason, recorded_by)"
                        + " VALUES (:id, 0, 'PENDING', NULL, NULL, :recordedBy)")
                .param("id", id.value())
                .param("recordedBy", RECORDER.subject())
                .update();

        assertThatThrownBy(() -> transactions.findById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accounting.journal_entry.amount")
                .hasMessageContaining(id.value().toString())
                .hasNoCause()
                .message()
                .doesNotContain("10.005");
    }

    /**
     * The port's tie-break: two transactions on one date come back by id. They are recorded larger id
     * first, so a store that returned them in insertion order would fail this.
     */
    @Test
    void Given_two_transactions_on_one_date_recorded_larger_id_first_When_read_Then_the_smaller_id_leads() {
        Transaction larger = record(LARGER_ID, MARCH_1, "Second by id", "7.50");
        Transaction smaller = record(SMALLER_ID, MARCH_1, "First by id", "12.50");

        assertThat(transactions.findByAccount(cash.id())).containsExactly(smaller, larger);
        assertThat(transactions.findBetween(MARCH_1, APRIL_1)).containsExactly(smaller, larger);
    }

    private Transaction record(LocalDate on, String description, String amount) {
        Transaction recorded = Transaction.record(
                on,
                description,
                List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount))));
        transactions.save(recorded, RECORDER);
        return recorded;
    }

    private Transaction record(UUID id, LocalDate on, String description, String amount) {
        Transaction recorded = new Transaction(
                new TransactionId(id),
                on,
                description,
                List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount))),
                new TransactionState.Pending());
        transactions.save(recorded, RECORDER);
        return recorded;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
