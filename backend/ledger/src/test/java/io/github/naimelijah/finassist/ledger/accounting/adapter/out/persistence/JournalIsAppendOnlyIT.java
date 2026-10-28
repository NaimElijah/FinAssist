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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The test that discharges ADR-020.
 *
 * <p>That record attaches an obligation to the commit which lands the first repository: <em>"no
 * {@code UPDATE} or {@code DELETE} on a recorded transaction's entries, and lifecycle changes
 * recorded as new state rather than as an overwrite. That is a property of the schema and its
 * migrations."</em> A guarantee that lived only in the adapter would hold until somebody wrote one
 * more statement, so what is asserted here is the database refusing, not the Java declining to ask.
 *
 * <p>The statements are issued directly rather than through the repository on purpose. Going through
 * the port would prove only that the port has no delete on it, which the compiler already knows.
 */
@SpringBootTest
class JournalIsAppendOnlyIT extends PostgresFixture {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_3 = LocalDate.of(2026, 3, 3);
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);

    private static final Actor RECORDER = new Actor("owner-user");

    private static final String TRANSACTION_TABLE = "journal_transaction";
    private static final String STATE_TABLE = "journal_transaction_state";

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    /**
     * Every statement the trigger exists to refuse, on every table it guards. {@code
     * journal_transaction_state} has no foreign key pointing at it, so nothing but the trigger stops a
     * {@code DELETE} of a {@code RECONCILED} row from quietly reverting the transaction to its previous
     * state; {@code journal_transaction}'s own {@code DELETE} would otherwise be refused only by the
     * foreign keys, for a reason that disappears with its children. The message names the operation
     * and the table, so each case proves its own trigger fired rather than a neighbour's.
     */
    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            UPDATE | journal_transaction       | UPDATE accounting.journal_transaction SET description = 'tampered' WHERE id = :id
            DELETE | journal_transaction       | DELETE FROM accounting.journal_transaction WHERE id = :id
            UPDATE | journal_entry             | UPDATE accounting.journal_entry SET amount = 1 WHERE transaction_id = :id
            DELETE | journal_entry             | DELETE FROM accounting.journal_entry WHERE transaction_id = :id
            UPDATE | journal_transaction_state | UPDATE accounting.journal_transaction_state SET state = 'VOIDED' WHERE transaction_id = :id
            DELETE | journal_transaction_state | DELETE FROM accounting.journal_transaction_state WHERE transaction_id = :id
            """)
    void Given_a_recorded_transaction_When_a_row_is_updated_or_deleted_Then_the_database_refuses_it(
            String operation, String table, String statement) {
        Transaction recorded = record("12.50");

        assertThatThrownBy(() ->
                        jdbc().sql(statement).param("id", recorded.id().value()).update())
                .hasMessageContaining("the journal is append-only: " + operation + " on accounting." + table
                        + " is refused (ADR-020)");
    }

    /**
     * The other half of ADR-020's sentence. A transition must leave the earlier states readable, or
     * the journal records only where a transaction ended up and not what happened to it.
     */
    @Test
    void Given_a_transaction_moved_through_its_lifecycle_When_the_rows_are_read_Then_every_state_is_still_there() {
        Transaction recorded = record("12.50");
        transactions.save(recorded.clear(MARCH_3), RECORDER);
        transactions.save(recorded.clear(MARCH_3).reconcile(MARCH_5), RECORDER);

        List<String> history = jdbc().sql(
                        "SELECT state FROM accounting.journal_transaction_state WHERE transaction_id = :id"
                                + " ORDER BY sequence")
                .param("id", recorded.id().value())
                .query(String.class)
                .list();

        assertThat(history).containsExactly("PENDING", "CLEARED", "RECONCILED");
        assertThat(transactions.findById(recorded.id()).orElseThrow().state())
                .isEqualTo(new TransactionState.Reconciled(MARCH_5));
    }

    /** Saving an unchanged state twice must not grow the history, or every read would append a row. */
    @Test
    void Given_a_transaction_saved_again_unchanged_When_the_rows_are_read_Then_no_state_was_appended() {
        Transaction recorded = record("12.50");

        transactions.save(recorded, RECORDER);
        transactions.save(recorded, RECORDER);

        assertThat(states(recorded)).isEqualTo(1);
    }

    /**
     * A correction is a new transaction and a mistake is voided with a reason. Silently accepting
     * different entries under an identity already recorded would be the rewrite ADR-020 forbids,
     * arriving through the one door the port leaves open.
     */
    @Test
    void Given_a_recorded_transaction_When_saved_with_different_entries_Then_it_is_refused() {
        Transaction recorded = record("12.50");
        Transaction rewritten = new Transaction(
                recorded.id(), recorded.occurredOn(), recorded.description(), entries("99.99"), recorded.state());

        assertThatThrownBy(() -> transactions.save(rewritten, RECORDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rewritten");
    }

    /**
     * {@code V1_2}'s {@code CHECK} is {@code NOT VALID}, which only excuses it from scanning history
     * already in the table — it still runs on every row written from now on. This is the other half
     * of ADR-033's obligation, proven the way this file proves the rest of ADR-020: against the
     * database directly, not through the repository. Each table carries its own constraint, so each
     * is tried with every shape the constraint exists to refuse: no recorder, a blank one, and one
     * past {@code Actor.MAX_SUBJECT_LENGTH}. "No recorder" here is an explicit {@code NULL}; the case
     * below leaves the column out instead.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("refusedRecorders")
    void Given_a_new_journal_row_When_inserted_with_an_unusable_recorder_Then_the_database_refuses_it(
            String table, String shape, String recordedBy) {
        assertThatThrownBy(() -> insertWithRecorder(table, recordedBy))
                .hasMessageContaining(table + "_recorded_by_shape");
    }

    /**
     * An insert that does not name {@code recorded_by} at all takes the column's default. The default
     * is {@code NULL} today, so the {@code CHECK} refuses the row; a {@code SET DEFAULT current_user},
     * or any other default, would quietly attribute the row to whoever the connection is, which is not
     * the token's subject. The explicit-{@code NULL} case above never reads the default, so only this
     * one fails for it.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {TRANSACTION_TABLE, STATE_TABLE})
    void Given_a_new_journal_row_When_inserted_without_naming_its_recorder_Then_the_database_refuses_it(String table) {
        assertThatThrownBy(() -> insertWithoutNamingTheRecorder(table))
                .hasMessageContaining(table + "_recorded_by_shape");
    }

    /** The boundary from the other side: the longest subject {@code Actor} admits is stored as given. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {TRANSACTION_TABLE, STATE_TABLE})
    void Given_a_new_journal_row_When_inserted_with_a_recorder_of_the_maximum_length_Then_it_is_stored(String table) {
        String longest = "s".repeat(Actor.MAX_SUBJECT_LENGTH);

        insertWithRecorder(table, longest);

        assertThat(jdbc().sql("SELECT count(*) FROM accounting." + table + " WHERE recorded_by = :recordedBy")
                        .param("recordedBy", longest)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    static Stream<Arguments> refusedRecorders() {
        return Stream.of(TRANSACTION_TABLE, STATE_TABLE)
                .flatMap(table -> Stream.of(
                        Arguments.of(table, "none", null),
                        Arguments.of(table, "blank", "   "),
                        Arguments.of(table, "one character too long", "s".repeat(Actor.MAX_SUBJECT_LENGTH + 1))));
    }

    /**
     * A {@code journal_transaction} row is written as a bare header, which the deferred balance check
     * never sees because it fires on entries. A {@code journal_transaction_state} row needs a
     * transaction to belong to, so one is recorded first and the row is its next state.
     */
    private void insertWithRecorder(String table, String recordedBy) {
        if (table.equals(TRANSACTION_TABLE)) {
            jdbc().sql("INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency,"
                            + " recorded_by) VALUES (:id, :on, 'Groceries', 'ILS', CAST(:recordedBy AS text))")
                    .param("id", TransactionId.generate().value())
                    .param("on", MARCH_1)
                    .param("recordedBy", recordedBy)
                    .update();
        } else {
            Transaction recorded = record("12.50");
            jdbc().sql("INSERT INTO accounting.journal_transaction_state (transaction_id, sequence, state,"
                            + " effective_on, reason, recorded_by)"
                            + " VALUES (:id, 1, 'CLEARED', :on, NULL, CAST(:recordedBy AS text))")
                    .param("id", recorded.id().value())
                    .param("on", MARCH_3)
                    .param("recordedBy", recordedBy)
                    .update();
        }
    }

    /** {@link #insertWithRecorder}'s rows, with {@code recorded_by} left out of the column list. */
    private void insertWithoutNamingTheRecorder(String table) {
        if (table.equals(TRANSACTION_TABLE)) {
            jdbc().sql("INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency)"
                            + " VALUES (:id, :on, 'Groceries', 'ILS')")
                    .param("id", TransactionId.generate().value())
                    .param("on", MARCH_1)
                    .update();
        } else {
            Transaction recorded = record("12.50");
            jdbc().sql("INSERT INTO accounting.journal_transaction_state (transaction_id, sequence, state,"
                            + " effective_on, reason) VALUES (:id, 1, 'CLEARED', :on, NULL)")
                    .param("id", recorded.id().value())
                    .param("on", MARCH_3)
                    .update();
        }
    }

    private int states(Transaction transaction) {
        return jdbc().sql("SELECT count(*) FROM accounting.journal_transaction_state WHERE transaction_id = :id")
                .param("id", transaction.id().value())
                .query(Integer.class)
                .single();
    }

    private Transaction record(String amount) {
        Transaction recorded = Transaction.record(MARCH_1, "Groceries", entries(amount));
        transactions.save(recorded, RECORDER);
        return recorded;
    }

    private List<Entry> entries(String amount) {
        Account cash = open("Cash", AccountType.ASSET);
        Account groceries = open("Groceries", AccountType.EXPENSE);
        return List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount)));
    }

    /** Reuses the account when it is already open, so an entries list can be built twice in one test. */
    private Account open(String name, AccountType type) {
        return accounts.findByName(name).orElseGet(() -> {
            Account account = Account.open(name, type, Currency.ILS);
            accounts.save(account);
            return account;
        });
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
