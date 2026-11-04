package io.github.naimelijah.finassist.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * A second writer whose transaction stays open across a test's statements, so a test can decide when a
 * rival's work becomes visible and make a race happen in one chosen order rather than hope for it.
 *
 * <p>Its own physical connection, with autocommit off and no transaction synchronisation. Spring's
 * transaction support binds a connection to the calling thread and ends the transaction with the
 * callback, and a pooled connection comes out of the pool the code under test needs. Here the test
 * thread opens, holds and ends the transaction while another thread runs into it.
 *
 * <p>Obtained from {@link PostgresFixture#rivalWriter()}, so {@code PostgresFixture}'s container is the
 * only database it can reach. A test holds it in try-with-resources: a transaction left open would
 * keep its locks, and the worker queued behind them would never finish.
 */
public final class RivalWriter implements AutoCloseable {

    /** Generous beside the milliseconds a blocked backend really takes, to survive a loaded CI machine. */
    private static final Duration BLOCKED_WITHIN = Duration.ofSeconds(10);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private static final String BACKENDS_BLOCKED_BY = """
            SELECT count(*) FROM pg_stat_activity WHERE :rival = ANY(pg_blocking_pids(pid))
            """;

    /**
     * PostgreSQL reports what a blocked backend waits for: {@code relation} for a table's lock, as
     * against {@code transactionid} for a key another transaction may yet commit.
     */
    private static final String BACKENDS_BLOCKED_ON_A_TABLE_LOCK_BY = """
            SELECT count(*) FROM pg_stat_activity
             WHERE :rival = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock' AND wait_event = 'relation'
            """;

    private final Connection connection;
    private final JdbcClient jdbc;
    private final JdbcClient observer;
    private final int backendPid;

    private RivalWriter(Connection connection, JdbcClient observer) {
        this.connection = connection;
        this.jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        this.observer = observer;
        this.backendPid =
                jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
        rollback();
    }

    /**
     * Opens the rival's connection.
     *
     * @param unpooled hands out a new physical connection per call: one is held for the rival's
     *     transaction, and each poll of {@link #awaitBlockingAnotherBackend} takes another
     * @throws IllegalStateException if the connection cannot be opened
     */
    static RivalWriter open(DataSource unpooled) {
        try {
            Connection connection = unpooled.getConnection();
            connection.setAutoCommit(false);
            return new RivalWriter(connection, JdbcClient.create(unpooled));
        } catch (SQLException failure) {
            throw new IllegalStateException("could not open the rival writer's connection", failure);
        }
    }

    /** Runs on the rival's connection, so what it writes stays invisible until {@link #commit}. */
    public JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * Commits the rival's transaction.
     *
     * @throws IllegalStateException if the database refuses, such as a deferred check failing
     */
    public void commit() {
        try {
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("the rival writer could not commit", failure);
        }
    }

    /**
     * Rolls the rival's transaction back.
     *
     * @throws IllegalStateException if the connection fails
     */
    public void rollback() {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            throw new IllegalStateException("the rival writer could not roll back", failure);
        }
    }

    /**
     * Returns once another backend is queued behind this one, which is the only proof that a worker has
     * reached the chosen point of the race and not merely been started.
     *
     * <p>Polled from a separate autocommit statement because {@code pg_stat_activity} is cached for the
     * length of a transaction: read from the rival's own, the first answer would be the answer forever.
     *
     * @throws org.awaitility.core.ConditionTimeoutException if nothing blocks within the bound,
     *     which means the worker finished or failed instead of reaching this rival
     */
    public void awaitBlockingAnotherBackend() {
        awaitBlocked(BACKENDS_BLOCKED_BY, "another backend blocked by rival ");
    }

    /**
     * As {@link #awaitBlockingAnotherBackend}, and only once the backend is queued on a table this rival
     * locked, for a test whose race happens at that point and would otherwise pass unnoticed with the
     * worker queued somewhere else behind the same rival.
     *
     * @throws org.awaitility.core.ConditionTimeoutException if nothing waits on a table within the bound
     */
    public void awaitBlockingAnotherBackendOnATableLock() {
        awaitBlocked(BACKENDS_BLOCKED_ON_A_TABLE_LOCK_BY, "another backend waiting on a table locked by rival ");
    }

    private void awaitBlocked(String query, String description) {
        // Awaitility otherwise hands any uncaught exception, on any thread of the JVM, to this wait.
        Awaitility.await(description + backendPid)
                .dontCatchUncaughtExceptions()
                .atMost(BLOCKED_WITHIN)
                .pollInterval(POLL_INTERVAL)
                .until(() -> backendsBlocked(query) > 0);
    }

    private int backendsBlocked(String query) {
        return observer.sql(query)
                .param("rival", backendPid)
                .query(Integer.class)
                .single();
    }

    /**
     * Rolls back whatever is still open, a no-op after {@link #commit} or {@link #rollback}, and closes
     * the connection, so a test that fails half way still releases what its worker is queued behind.
     *
     * @throws IllegalStateException if the connection fails
     */
    @Override
    public void close() {
        try (connection) {
            connection.rollback();
        } catch (SQLException failure) {
            throw new IllegalStateException("the rival writer could not close", failure);
        }
    }
}
