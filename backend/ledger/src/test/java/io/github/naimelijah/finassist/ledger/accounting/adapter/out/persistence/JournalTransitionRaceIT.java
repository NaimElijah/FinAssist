package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.RivalWriter;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ConcurrentTransitionException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * A lifecycle move is stored only against the state it was validated on (#370).
 *
 * <p>A move is decided on one read of a transaction and written later, and {@code V1_4} checks each new
 * row only against the state stored by then. Left at that, a stale move the stored state would allow
 * lands: a clear and a void decided on one read of a {@code PENDING} transaction store {@code PENDING,
 * CLEARED, VOIDED}, though whoever voided it never saw it cleared, and the append-only journal makes that
 * permanent. Every other stale move answers 500, as a database error nothing maps.
 *
 * <p>The races are forced, never hoped for. A move can lose in three places, and each has its own case:
 * after the rival has committed, while the rival's row is uncommitted, and between the worker's read of
 * the state and its insert. {@link RivalWriter} holds the rival open until the worker is seen blocked on
 * it, so every interleaving here happens on every run.
 */
@SpringBootTest
class JournalTransitionRaceIT extends PostgresFixture {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_3 = LocalDate.of(2026, 3, 3);
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);

    private static final String VOID_REASON = "entered twice";

    private static final Actor RECORDER = new Actor("owner-user");
    private static final Actor LOSER = new Actor("second-writer");
    private static final String RIVAL_SUBJECT = "rival-writer";

    /** Outlasts {@link RivalWriter}'s own wait, so a worker that never returns fails here, by name. */
    private static final long WORKER_BOUND_SECONDS = 15;

    private static final List<String> RECORDED_THEN_RIVAL_CLEARED = List.of("0:PENDING", "1:CLEARED");

    private static final String RIVAL_CLEARS = """
            INSERT INTO accounting.journal_transaction_state
                   (transaction_id, sequence, state, effective_on, reason, recorded_by)
            VALUES (:id, 1, 'CLEARED', :on, NULL, :recordedBy)
            """;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private DataSource dataSource;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Account cash;
    private Account groceries;

    /**
     * Two moves made from one read, in each order that matters. A pair is listed in both orders because
     * the two fail differently without the check: one move on top of another can be legal, so it is
     * stored, or illegal, so the database's trigger refuses it with an error nothing maps. Clearing
     * twice on one date is the case the store could mistake for a re-save of what it already holds.
     */
    enum TwoMoves {
        CLEAR_THEN_VOID(
                false,
                read -> read.clear(MARCH_3),
                read -> read.markVoided(MARCH_3, VOID_REASON),
                List.of("0:PENDING", "1:CLEARED")),
        VOID_THEN_CLEAR(
                false,
                read -> read.markVoided(MARCH_3, VOID_REASON),
                read -> read.clear(MARCH_3),
                List.of("0:PENDING", "1:VOIDED")),
        CLEAR_THEN_CLEAR_ON_THE_SAME_DATE(
                false, read -> read.clear(MARCH_3), read -> read.clear(MARCH_3), List.of("0:PENDING", "1:CLEARED")),
        CLEAR_THEN_CLEAR_ON_A_LATER_DATE(
                false, read -> read.clear(MARCH_3), read -> read.clear(MARCH_5), List.of("0:PENDING", "1:CLEARED")),
        RECONCILE_THEN_VOID(
                true,
                read -> read.reconcile(MARCH_5),
                read -> read.markVoided(MARCH_5, VOID_REASON),
                List.of("0:PENDING", "1:CLEARED", "2:RECONCILED")),
        VOID_THEN_RECONCILE(
                true,
                read -> read.markVoided(MARCH_5, VOID_REASON),
                read -> read.reconcile(MARCH_5),
                List.of("0:PENDING", "1:CLEARED", "2:VOIDED"));

        private final boolean startsCleared;
        private final UnaryOperator<Transaction> first;
        private final UnaryOperator<Transaction> second;
        private final List<String> historyOfOnlyTheFirst;

        TwoMoves(
                boolean startsCleared,
                UnaryOperator<Transaction> first,
                UnaryOperator<Transaction> second,
                List<String> historyOfOnlyTheFirst) {
            this.startsCleared = startsCleared;
            this.first = first;
            this.second = second;
            this.historyOfOnlyTheFirst = historyOfOnlyTheFirst;
        }
    }

    @BeforeEach
    void openTheChartOfAccounts() {
        cash = Account.open("Cash", AccountType.ASSET, Currency.ILS);
        groceries = Account.open("Groceries", AccountType.EXPENSE, Currency.ILS);
        accounts.save(cash);
        accounts.save(groceries);
    }

    /** Each test has ended its rival by now, so the worker has nothing left to wait for. */
    @AfterEach
    void shutTheWorkerDown() throws InterruptedException {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(WORKER_BOUND_SECONDS, TimeUnit.SECONDS))
                .as("the worker finished once its rival had ended")
                .isTrue();
    }

    /**
     * The whole bug with no thread in sight: by the time the second move is written, the first has been
     * committed for as long as anyone likes, and only the state the second was validated against can
     * tell the store that its decision is stale.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(TwoMoves.class)
    void
            Given_two_moves_validated_against_one_read_When_the_second_is_saved_Then_it_is_refused_and_only_the_first_is_stored(
                    TwoMoves moves) {
        TransactionId id = recordedFor(moves);
        Transaction read = transactions.findById(id).orElseThrow();

        transactions.saveTransition(read.state(), moves.first.apply(read), RECORDER);
        Throwable refusal =
                catchThrowable(() -> transactions.saveTransition(read.state(), moves.second.apply(read), LOSER));

        assertRefusedLeaving(refusal, id, moves.historyOfOnlyTheFirst);
    }

    /**
     * The worker read {@code PENDING} and is queued on the rival's uncommitted row at its own sequence.
     * Once the rival commits, that queue ends in a key collision, which must reach the caller as the
     * stale move it is rather than as a database error answered 500.
     */
    @Test
    void Given_a_rivals_uncommitted_move_When_it_commits_under_a_blocked_transition_Then_the_transition_is_refused()
            throws Exception {
        Transaction read = recorded();

        Throwable outcome;
        try (RivalWriter rival = rivalWriter()) {
            rivalClears(rival, read.id());
            Future<?> voiding = voidInTheBackground(read);
            rival.awaitBlockingAnotherBackend();

            rival.commit();
            outcome = outcomeOf(voiding);
        }

        assertRefusedLeaving(outcome, read.id(), RECORDED_THEN_RIVAL_CLEARED);
    }

    /**
     * A rival that gives up changed nothing, so the state the worker validated against is still the
     * stored one and its move is good. A store that refused on sight of a lock would turn every aborted
     * neighbour into a spurious conflict for the owner to retry.
     */
    @Test
    void Given_a_rivals_uncommitted_move_When_it_rolls_back_under_a_blocked_transition_Then_the_transition_is_stored()
            throws Exception {
        Transaction read = recorded();

        Throwable outcome;
        try (RivalWriter rival = rivalWriter()) {
            rivalClears(rival, read.id());
            Future<?> voiding = voidInTheBackground(read);
            rival.awaitBlockingAnotherBackend();

            rival.rollback();
            outcome = outcomeOf(voiding);
        }

        assertThat(outcome).as("what the worker threw").isNull();
        assertThat(history(read.id())).containsExactly("0:PENDING", "1:VOIDED");
    }

    /**
     * The narrowest window: the rival commits after the worker has read the state and before it inserts.
     *
     * <p>This interleaving is why the adapter runs at {@code REPEATABLE READ}. At {@code READ COMMITTED}
     * every statement takes a new snapshot, so the {@code BEFORE INSERT} trigger, {@code
     * refuse_illegal_transition}, would read the rival's row, now committed, and raise "the next state is
     * sequence 2, not 1", which is no key collision and would answer 500. Here the trigger shares the
     * snapshot the worker's read of the state took, sees what that read saw, and admits the row, so the
     * move ends in the same key collision as one queued on an uncommitted rival. Compared to the state it
     * read, the worker's move looks fine, so nothing but the primary key knows.
     *
     * <p>To hold the worker inside the window, the rival takes the journal's entry table as well as
     * writing its row. The worker's read of the state wants no lock the rival holds and cannot see the
     * uncommitted row, so it goes through and sees {@code PENDING}; its next read, in {@code
     * refuseChangedEntries}, joins the entries and queues on the lock.
     * <strong>This relies on the adapter reading the state before the entries.</strong> With the
     * reads swapped the worker would queue before it had read the state: it would still be refused, so
     * this test stays green, but it would no longer prove anything about this window, and it would need
     * another way to hold the worker here.
     */
    @Test
    void Given_a_rival_committing_between_the_state_read_and_the_insert_When_the_transition_goes_on_Then_it_is_refused()
            throws Exception {
        Transaction read = recorded();

        Throwable outcome;
        try (RivalWriter rival = rivalWriter()) {
            rival.jdbc()
                    .sql("LOCK TABLE accounting.journal_entry IN ACCESS EXCLUSIVE MODE")
                    .update();
            rivalClears(rival, read.id());
            Future<?> voiding = voidInTheBackground(read);
            rival.awaitBlockingAnotherBackend();

            rival.commit();
            outcome = outcomeOf(voiding);
        }

        assertRefusedLeaving(outcome, read.id(), RECORDED_THEN_RIVAL_CLEARED);
    }

    /**
     * The adapter's isolation level is set on a connection that then goes back to the pool. One that
     * came back still at {@code REPEATABLE READ} would silently change every later {@code save} on it,
     * where {@code V1_6}'s key could then raise a serialization failure nothing maps.
     */
    @Test
    void Given_a_stored_transition_When_every_pooled_connection_is_asked_Then_each_is_back_at_read_committed()
            throws Exception {
        Transaction read = recorded();

        transactions.saveTransition(read.state(), read.clear(MARCH_3), RECORDER);

        assertThat(isolationOfEveryPooledConnection()).containsOnly("read committed");
    }

    /** Borrowed all at once, so each answer comes from a different connection and none is missed. */
    private List<String> isolationOfEveryPooledConnection() throws SQLException {
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        List<Connection> borrowed = new ArrayList<>();
        try {
            List<String> levels = new ArrayList<>();
            for (int taken = 0; taken < poolSize; taken++) {
                Connection connection = dataSource.getConnection();
                borrowed.add(connection);
                levels.add(JdbcClient.create(new SingleConnectionDataSource(connection, true))
                        .sql("SHOW transaction_isolation")
                        .query(String.class)
                        .single());
            }
            return levels;
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
    }

    /** Soft, so a store that lets the stale move through shows the history it wrote as well as no refusal. */
    private void assertRefusedLeaving(Throwable refusal, TransactionId id, List<String> stored) {
        assertSoftly(softly -> {
            softly.assertThat(refusal)
                    .as("what the stale move threw")
                    .isInstanceOf(ConcurrentTransitionException.class);
            softly.assertThat(history(id)).as("the stored history").isEqualTo(stored);
        });
    }

    /** Voids on the worker's thread, validated against what {@code read} says, as the use case would. */
    private Future<?> voidInTheBackground(Transaction read) {
        return worker.submit(
                () -> transactions.saveTransition(read.state(), read.markVoided(MARCH_5, VOID_REASON), LOSER));
    }

    /** What the worker threw, or {@code null} if it returned; a timeout is itself a failure to report. */
    private static Throwable outcomeOf(Future<?> running) throws Exception {
        try {
            running.get(WORKER_BOUND_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException thrown) {
            return thrown.getCause();
        }
    }

    private static void rivalClears(RivalWriter rival, TransactionId id) {
        rival.jdbc()
                .sql(RIVAL_CLEARS)
                .param("id", id.value())
                .param("on", MARCH_3)
                .param("recordedBy", RIVAL_SUBJECT)
                .update();
    }

    private TransactionId recordedFor(TwoMoves moves) {
        Transaction recorded = recorded();
        if (moves.startsCleared) {
            transactions.saveTransition(recorded.state(), recorded.clear(MARCH_3), RECORDER);
        }
        return recorded.id();
    }

    private Transaction recorded() {
        Transaction recorded = Transaction.record(
                MARCH_1,
                "Groceries",
                List.of(new Entry(groceries.id(), ils("12.50")), new Entry(cash.id(), ils("-12.50"))));
        transactions.save(recorded, RECORDER);
        return recorded;
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
