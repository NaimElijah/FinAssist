package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lifecycle a transaction's state history may record, held by the database as well as by {@link
 * Transaction} (ADR-006, ADR-020).
 *
 * <p>A lifecycle change is an {@code INSERT} into {@code journal_transaction_state}, and the current
 * state is the row with the highest {@code sequence}. A row the domain could never have produced would
 * therefore rewrite the record by addition: a {@code PENDING} row after a {@code RECONCILED} one
 * un-reconciles a frozen transaction without touching a row the append-only triggers guard. So each
 * row is checked against the one before it: the history opens {@code PENDING} at sequence 0, each
 * later row takes the next sequence, moves only along a transition {@link Transaction} allows, and
 * never dates itself before the transaction occurred or before the state it follows.
 *
 * <p>Most refused rows are written directly, as {@code JournalIsAppendOnlyIT} writes its own, because
 * the adapter cannot write them: it always appends at the stored sequence plus one, and an aggregate
 * {@link Transaction} refuses never reaches it. Two refusals are reachable through the repository,
 * from aggregates {@link Transaction} allows, and are tested through it: a first save that is not
 * {@code Pending}, and a save two moves ahead of the state stored.
 */
@SpringBootTest
class JournalStateHistoryIT extends PostgresFixture {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_3 = LocalDate.of(2026, 3, 3);
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);

    private static final Actor RECORDER = new Actor("owner-user");

    private static final String VOID_REASON = "entered twice";

    private static final String INSERT_STATE = """
            INSERT INTO accounting.journal_transaction_state
                   (transaction_id, sequence, state, effective_on, reason, recorded_by)
            VALUES (:id, :sequence, :state, CAST(:effectiveOn AS date), CAST(:reason AS text), :recordedBy)
            """;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Account cash;
    private Account groceries;

    @BeforeEach
    void openTheChartOfAccounts() {
        cash = Account.open("Cash", AccountType.ASSET, Currency.ILS);
        groceries = Account.open("Groceries", AccountType.EXPENSE, Currency.ILS);
        accounts.save(cash);
        accounts.save(groceries);
    }

    /**
     * A history that opens anywhere but {@code PENDING} at sequence 0 records a transaction as
     * confirmed, or void, before anything confirmed it. The header and its entries are written in the
     * same database transaction as the refused row, the way the repository writes them, so the only
     * thing wrong with it is its opening state.
     */
    @ParameterizedTest(name = "{1} at sequence {0}")
    @CsvSource({"0, CLEARED", "0, VOIDED", "1, PENDING"})
    void Given_a_transaction_with_no_state_When_its_history_opens_other_than_pending_at_zero_Then_it_is_refused(
            int sequence, String state) {
        TransactionId id = TransactionId.generate();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    insertHeaderAndEntries(id);
                    insertState(id, sequence, state, APRIL_1);
                }))
                .hasMessageContaining(
                        "transaction " + id.value() + " opens PENDING at sequence 0, not " + state + " at " + sequence);
        assertThat(rows(id)).isZero();
    }

    /**
     * The current state is the highest sequence, so a gap would let a row jump the queue, and a
     * repeated sequence would be two current states; the primary key refuses the second, this the first.
     */
    @Test
    void Given_a_recorded_transaction_When_a_state_skips_a_sequence_Then_it_is_refused() {
        Transaction recorded = record();

        assertThatThrownBy(() -> insertState(recorded.id(), 2, "CLEARED", MARCH_3))
                .hasMessageContaining("transaction " + recorded.id().value() + ": the next state is sequence 1, not 2");
    }

    /**
     * Every move {@link Transaction} does not offer. {@code RECONCILED} and {@code VOIDED} are terminal;
     * a {@code PENDING} transaction cannot reconcile without clearing; nothing returns to {@code PENDING}
     * or repeats the state it is in.
     */
    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({
        "PENDING, PENDING",
        "PENDING, RECONCILED",
        "CLEARED, PENDING",
        "CLEARED, CLEARED",
        "RECONCILED, PENDING",
        "RECONCILED, CLEARED",
        "RECONCILED, RECONCILED",
        "RECONCILED, VOIDED",
        "VOIDED, PENDING",
        "VOIDED, CLEARED",
        "VOIDED, VOIDED"
    })
    void Given_a_transaction_in_a_state_When_a_transition_the_domain_forbids_is_inserted_Then_it_is_refused(
            String from, String to) {
        Transaction current = recordIn(from);

        assertThatThrownBy(() -> insertState(current.id(), rows(current.id()), to, APRIL_1))
                .hasMessageContaining(
                        "transaction " + current.id().value() + " cannot move from " + from + " to " + to);
    }

    /**
     * A transaction moves forward in time: it cannot clear or be voided before it occurred, reconcile
     * before it cleared, or be voided before it cleared. Each case is one day too early for its rule.
     *
     * <p>The refusal reaches the log with every 5xx that carries it, and financial data is redacted at
     * the logging boundary, not echoed by the database: the message names the transaction and the
     * states, never a date — neither the one refused nor the ones it is measured against.
     */
    @ParameterizedTest(name = "{1} from {0}")
    @CsvSource({
        "PENDING, CLEARED, 2026-02-28",
        "PENDING, VOIDED, 2026-02-28",
        "CLEARED, RECONCILED, 2026-03-02",
        "CLEARED, VOIDED, 2026-03-02"
    })
    void Given_a_transaction_in_a_state_When_the_next_one_is_dated_too_early_Then_it_is_refused(
            String from, String to, LocalDate effectiveOn) {
        Transaction current = recordIn(from);
        String id = current.id().value().toString();

        Throwable refusal = catchThrowable(() -> insertState(current.id(), rows(current.id()), to, effectiveOn));

        assertThat(refusal)
                .hasMessageContaining(
                        "transaction " + id + " cannot be " + to + " before it occurred or before its previous state");
        // The id is a random UUID, which spells a year now and then; the rest of the message must not.
        assertThat(refusal.getMessage().replace(id, ""))
                .doesNotContain(
                        effectiveOn.toString(),
                        MARCH_1.toString(),
                        MARCH_3.toString(),
                        String.valueOf(effectiveOn.getYear()));
    }

    /**
     * The whole of the lifecycle the domain allows, written through the repository, so the trigger is
     * shown to admit every row the adapter writes. Each move is dated on the day of the one before it,
     * the earliest the rule allows.
     */
    @Test
    void Given_a_recorded_transaction_When_cleared_and_reconciled_Then_every_state_is_stored() {
        Transaction recorded = record();

        transactions.save(recorded.clear(MARCH_1), RECORDER);
        transactions.save(recorded.clear(MARCH_1).reconcile(MARCH_1), RECORDER);

        assertThat(history(recorded.id())).containsExactly("0:PENDING", "1:CLEARED", "2:RECONCILED");
    }

    @Test
    void Given_a_recorded_transaction_When_voided_Then_both_states_are_stored() {
        Transaction recorded = record();

        transactions.save(recorded.markVoided(MARCH_1, VOID_REASON), RECORDER);

        assertThat(history(recorded.id())).containsExactly("0:PENDING", "1:VOIDED");
    }

    @Test
    void Given_a_cleared_transaction_When_voided_Then_every_state_is_stored() {
        Transaction cleared = record().clear(MARCH_3);
        transactions.save(cleared, RECORDER);

        transactions.save(cleared.markVoided(MARCH_5, VOID_REASON), RECORDER);

        assertThat(history(cleared.id())).containsExactly("0:PENDING", "1:CLEARED", "2:VOIDED");
    }

    /**
     * The port's save contract, held through the port: {@link Transaction} allows clearing and then
     * reconciling in memory, but a save that stores both at once would record a clear nobody saved, so
     * the history refuses it and stays as it was.
     */
    @Test
    void Given_a_pending_transaction_When_saved_cleared_and_reconciled_at_once_Then_it_is_refused() {
        Transaction recorded = record();
        Transaction twoMovesAhead = recorded.clear(MARCH_3).reconcile(MARCH_5);

        assertThatThrownBy(() -> transactions.save(twoMovesAhead, RECORDER))
                .rootCause()
                .hasMessageContaining(
                        "transaction " + recorded.id().value() + " cannot move from PENDING to RECONCILED");
        assertThat(history(recorded.id())).containsExactly("0:PENDING");
    }

    /**
     * A transaction first saved already cleared would open its history confirmed, with no record of the
     * pending state it was entered in, so nothing of it is stored: the header and entries roll back
     * with the refused state.
     */
    @Test
    void Given_a_new_transaction_When_its_first_save_is_cleared_Then_nothing_is_recorded() {
        Transaction cleared = Transaction.record(
                        MARCH_1,
                        "Groceries",
                        List.of(new Entry(groceries.id(), ils("12.50")), new Entry(cash.id(), ils("-12.50"))))
                .clear(MARCH_3);

        assertThatThrownBy(() -> transactions.save(cleared, RECORDER))
                .rootCause()
                .hasMessageContaining(
                        "transaction " + cleared.id().value() + " opens PENDING at sequence 0, not CLEARED at 0");
        assertThat(rows(cleared.id())).isZero();
        assertThat(headers(cleared.id())).isZero();
    }

    /** Recorded on {@code MARCH_1}, then moved through the domain to {@code state}, a step at a time. */
    private Transaction recordIn(String state) {
        Transaction current = record();
        if (state.equals("CLEARED") || state.equals("RECONCILED")) {
            current = saved(current.clear(MARCH_3));
        }
        if (state.equals("RECONCILED")) {
            current = saved(current.reconcile(MARCH_5));
        }
        if (state.equals("VOIDED")) {
            current = saved(current.markVoided(MARCH_3, VOID_REASON));
        }
        return current;
    }

    private Transaction saved(Transaction transaction) {
        transactions.save(transaction, RECORDER);
        return transaction;
    }

    private Transaction record() {
        Transaction recorded = Transaction.record(
                MARCH_1,
                "Groceries",
                List.of(new Entry(groceries.id(), ils("12.50")), new Entry(cash.id(), ils("-12.50"))));
        transactions.save(recorded, RECORDER);
        return recorded;
    }

    private void insertHeaderAndEntries(TransactionId id) {
        jdbc().sql("INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency, recorded_by)"
                        + " VALUES (:id, :on, 'Groceries', 'ILS', :recordedBy)")
                .param("id", id.value())
                .param("on", MARCH_1)
                .param("recordedBy", RECORDER.subject())
                .update();
        jdbc().sql("""
                        INSERT INTO accounting.journal_entry (transaction_id, ordinal, account_id, amount, currency)
                        VALUES (:id, 0, :groceries, 12.50, 'ILS'),
                               (:id, 1, :cash, -12.50, 'ILS')
                        """)
                .param("id", id.value())
                .param("groceries", groceries.id().value())
                .param("cash", cash.id().value())
                .update();
    }

    /**
     * One state row in the shape {@code transaction_state_shape} demands of {@code state}, so the row
     * reaches the transition rule rather than being refused for its shape: {@code PENDING} carries no
     * date, and only {@code VOIDED} a reason.
     */
    private void insertState(TransactionId id, int sequence, String state, LocalDate effectiveOn) {
        jdbc().sql(INSERT_STATE)
                .param("id", id.value())
                .param("sequence", sequence)
                .param("state", state)
                .param("effectiveOn", state.equals("PENDING") ? null : effectiveOn)
                .param("reason", state.equals("VOIDED") ? VOID_REASON : null)
                .param("recordedBy", RECORDER.subject())
                .update();
    }

    private int rows(TransactionId id) {
        return jdbc().sql("SELECT count(*) FROM accounting.journal_transaction_state WHERE transaction_id = :id")
                .param("id", id.value())
                .query(Integer.class)
                .single();
    }

    private int headers(TransactionId id) {
        return jdbc().sql("SELECT count(*) FROM accounting.journal_transaction WHERE id = :id")
                .param("id", id.value())
                .query(Integer.class)
                .single();
    }

    private List<String> history(TransactionId id) {
        return jdbc().sql("SELECT sequence || ':' || state FROM accounting.journal_transaction_state"
                        + " WHERE transaction_id = :id ORDER BY sequence")
                .param("id", id.value())
                .query(String.class)
                .list();
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
