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
import org.junit.jupiter.api.Test;
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

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Test
    void Given_a_recorded_entry_When_an_update_is_attempted_Then_the_database_refuses_it() {
        Transaction recorded = record("12.50");

        assertThatThrownBy(() -> jdbc().sql("UPDATE accounting.journal_entry SET amount = 1 WHERE transaction_id = :id")
                        .param("id", recorded.id().value())
                        .update())
                .hasMessageContaining("append-only")
                .hasMessageContaining("ADR-020");
    }

    @Test
    void Given_a_recorded_entry_When_a_delete_is_attempted_Then_the_database_refuses_it() {
        Transaction recorded = record("12.50");

        assertThatThrownBy(() -> jdbc().sql("DELETE FROM accounting.journal_entry WHERE transaction_id = :id")
                        .param("id", recorded.id().value())
                        .update())
                .hasMessageContaining("append-only");
    }

    @Test
    void Given_a_recorded_transaction_When_its_description_is_rewritten_Then_the_database_refuses_it() {
        Transaction recorded = record("12.50");

        assertThatThrownBy(() -> jdbc().sql(
                                "UPDATE accounting.journal_transaction SET description = 'tampered' WHERE id = :id")
                        .param("id", recorded.id().value())
                        .update())
                .hasMessageContaining("append-only");
    }

    @Test
    void Given_a_stored_state_When_it_is_overwritten_Then_the_database_refuses_it() {
        Transaction recorded = record("12.50");

        assertThatThrownBy(() -> jdbc().sql("UPDATE accounting.journal_transaction_state SET state = 'VOIDED' WHERE"
                                + " transaction_id = :id")
                        .param("id", recorded.id().value())
                        .update())
                .hasMessageContaining("append-only");
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
     * database directly, not through the repository.
     */
    @Test
    void Given_a_new_transaction_When_inserted_without_a_recorder_Then_the_database_refuses_it() {
        TransactionId id = TransactionId.generate();

        assertThatThrownBy(() -> jdbc().sql(
                                "INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency)"
                                        + " VALUES (:id, :on, 'Groceries', 'ILS')")
                        .param("id", id.value())
                        .param("on", MARCH_1)
                        .update())
                .hasMessageContaining("journal_transaction_recorded_by_shape");
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
