package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The journal, in PostgreSQL, and the class ADR-020's obligation lands on.
 *
 * <p><strong>There is no {@code UPDATE} and no {@code DELETE} in this file.</strong> That is the
 * point of writing the SQL by hand rather than generating it: the guarantee is one a reader can
 * check. Recording a transaction inserts it, its entries and its first state; every later
 * transition inserts one more state row, and the current state is the highest {@code sequence}.
 * Voiding keeps the entries and stops them counting, which {@code Transaction.countsTowardsBalance}
 * decides, not this class.
 *
 * <p>The database enforces the same thing independently: triggers on all three tables refuse an
 * update or a delete outright, so a statement added here in future fails rather than quietly
 * rewriting the record (ADR-024).
 *
 * <p>Both list queries return their results ordered by date and then by identity, which the port
 * makes part of its contract - a projection and a budget review both fold over these lists, and a
 * fold whose result depends on row order the database never promised is a test that passes until it
 * does not.
 */
public class JdbcTransactionRepository implements TransactionRepository {

    private static final String ENTRY_TABLE = "accounting.journal_entry";
    private static final String STATE_TABLE = "accounting.journal_transaction_state";

    /**
     * Every read starts here. The lateral join takes the newest state row per transaction, and the
     * entry join fans out one row per entry; {@link #toTransactions} regroups them. One statement
     * rather than one per transaction, because {@code ViewJournalUseCase} folds over a whole period
     * and an N+1 there is a thousand round trips per review.
     */
    private static final String SELECT = """
            SELECT t.id            AS transaction_id,
                   t.occurred_on   AS occurred_on,
                   t.description   AS description,
                   s.state         AS state,
                   s.effective_on  AS effective_on,
                   s.reason        AS reason,
                   e.ordinal       AS ordinal,
                   e.account_id    AS account_id,
                   e.amount        AS amount,
                   e.currency      AS currency
              FROM accounting.journal_transaction t
              JOIN LATERAL (SELECT state, effective_on, reason
                              FROM accounting.journal_transaction_state
                             WHERE transaction_id = t.id
                             ORDER BY sequence DESC
                             LIMIT 1) s ON TRUE
              JOIN accounting.journal_entry e ON e.transaction_id = t.id
             WHERE %s
             ORDER BY t.occurred_on, t.id, e.ordinal
            """;

    private static final String BY_ID = "t.id = :id";

    /**
     * Every entry of a transaction that touches the account, not only the entries that touch it -
     * hence the subquery rather than a predicate on the joined row. A transaction loaded with half
     * its entries would not satisfy its own constructor.
     */
    private static final String TOUCHING_ACCOUNT =
            "EXISTS (SELECT 1 FROM accounting.journal_entry x WHERE x.transaction_id = t.id AND x.account_id = :account)";

    private static final String IN_RANGE = "t.occurred_on >= :fromInclusive AND t.occurred_on < :toExclusive";

    private static final String INSERT_TRANSACTION = """
            INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency)
            VALUES (:id, :occurredOn, :description, :currency)
            """;

    private static final String INSERT_ENTRY = """
            INSERT INTO accounting.journal_entry (transaction_id, ordinal, account_id, amount, currency)
            VALUES (:transactionId, :ordinal, :accountId, :amount, :currency)
            """;

    private static final String INSERT_STATE = """
            INSERT INTO accounting.journal_transaction_state
                   (transaction_id, sequence, state, effective_on, reason)
            VALUES (:transactionId, :sequence, :state, :effectiveOn, :reason)
            """;

    private static final String SELECT_CURRENT_STATE = """
            SELECT sequence, state, effective_on, reason
              FROM accounting.journal_transaction_state
             WHERE transaction_id = :transactionId
             ORDER BY sequence DESC
             LIMIT 1
            """;

    private static final int FIRST_STATE = 0;

    private final JdbcClient jdbc;

    public JdbcTransactionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Transaction> findById(TransactionId id) {
        Objects.requireNonNull(id, "id");
        List<Transaction> found = toTransactions(jdbc.sql(SELECT.formatted(BY_ID))
                .param("id", id.value())
                .query(Row.MAPPER)
                .list());
        return found.stream().findFirst();
    }

    @Override
    public List<Transaction> findByAccount(AccountId account) {
        Objects.requireNonNull(account, "account");
        return toTransactions(jdbc.sql(SELECT.formatted(TOUCHING_ACCOUNT))
                .param("account", account.value())
                .query(Row.MAPPER)
                .list());
    }

    @Override
    public List<Transaction> findBetween(LocalDate fromInclusive, LocalDate toExclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        return toTransactions(jdbc.sql(SELECT.formatted(IN_RANGE))
                .param("fromInclusive", fromInclusive)
                .param("toExclusive", toExclusive)
                .query(Row.MAPPER)
                .list());
    }

    /**
     * {@inheritDoc}
     *
     * <p>First sight of an identity writes the transaction, its entries and its opening state.
     * Every later call writes at most one state row. Transactional because those are several
     * statements describing one fact: a half-written transaction would be an unbalanced one, which
     * the deferred constraint refuses at commit anyway.
     *
     * @throws IllegalStateException if the entries differ from those already recorded. Rewriting a
     *     posted entry is what ADR-020 forbids, and failing loudly is the only honest answer -
     *     ignoring the difference would leave the caller believing a correction had been stored.
     */
    @Override
    @Transactional
    public void save(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        Optional<Sequenced> current = currentState(transaction.id());
        if (current.isEmpty()) {
            insert(transaction);
            return;
        }
        refuseChangedEntries(transaction);
        appendStateIfChanged(transaction, current.get());
    }

    private void insert(Transaction transaction) {
        jdbc.sql(INSERT_TRANSACTION)
                .param("id", transaction.id().value())
                .param("occurredOn", transaction.occurredOn())
                .param("description", transaction.description())
                .param("currency", transaction.currency().name())
                .update();

        List<Entry> entries = transaction.entries();
        for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
            Entry entry = entries.get(ordinal);
            jdbc.sql(INSERT_ENTRY)
                    .param("transactionId", transaction.id().value())
                    .param("ordinal", ordinal)
                    .param("accountId", entry.account().value())
                    .param("amount", entry.amount().amount())
                    .param("currency", entry.amount().currency().name())
                    .update();
        }

        insertState(transaction.id(), FIRST_STATE, StoredState.of(transaction.state()));
    }

    private void refuseChangedEntries(Transaction transaction) {
        List<Transaction> found = toTransactions(jdbc.sql(SELECT.formatted(BY_ID))
                .param("id", transaction.id().value())
                .query(Row.MAPPER)
                .list());
        // Unreachable through this adapter - insert writes the transaction, its entries and its
        // state together - but this method exists to explain a bad write, and answering a
        // corrupted store with a bare NoSuchElementException would be the opposite of that.
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "Transaction " + transaction.id() + " has a recorded state but no entries; the journal is corrupt");
        }
        List<Entry> stored = found.getFirst().entries();
        if (!stored.equals(transaction.entries())) {
            throw new IllegalStateException("Transaction " + transaction.id()
                    + " is already recorded and its entries cannot be rewritten; a correction is a"
                    + " new transaction, and a mistake is voided with a reason (ADR-020)");
        }
    }

    private void appendStateIfChanged(Transaction transaction, Sequenced current) {
        StoredState wanted = StoredState.of(transaction.state());
        if (!wanted.equals(current.state())) {
            insertState(transaction.id(), current.sequence() + 1, wanted);
        }
    }

    private void insertState(TransactionId id, int sequence, StoredState state) {
        jdbc.sql(INSERT_STATE)
                .param("transactionId", id.value())
                .param("sequence", sequence)
                .param("state", state.state())
                .param("effectiveOn", state.effectiveOn())
                .param("reason", state.reason())
                .update();
    }

    private Optional<Sequenced> currentState(TransactionId id) {
        return jdbc.sql(SELECT_CURRENT_STATE)
                .param("transactionId", id.value())
                .query((row, rowNumber) -> new Sequenced(
                        row.getInt("sequence"),
                        new StoredState(
                                row.getString("state"),
                                row.getObject("effective_on", LocalDate.class),
                                row.getString("reason"))))
                .optional();
    }

    /**
     * Regroups the fanned-out join back into aggregates.
     *
     * <p>A {@link LinkedHashMap} because the query's {@code ORDER BY} is the contract: the map
     * preserves it, where grouping into a hash map would return the fold's inputs in an order
     * nothing promised.
     */
    private static List<Transaction> toTransactions(List<Row> rows) {
        Map<UUID, List<Row>> byTransaction = new LinkedHashMap<>();
        for (Row row : rows) {
            byTransaction
                    .computeIfAbsent(row.transactionId(), id -> new ArrayList<>())
                    .add(row);
        }
        return byTransaction.values().stream()
                .map(JdbcTransactionRepository::toTransaction)
                .toList();
    }

    private static Transaction toTransaction(List<Row> rows) {
        Row first = rows.getFirst();
        List<Entry> entries = rows.stream()
                .map(row -> new Entry(new AccountId(row.accountId()), row.amount()))
                .toList();
        return new Transaction(
                new TransactionId(first.transactionId()),
                first.occurredOn(),
                first.description(),
                entries,
                first.state().toDomain());
    }

    /** One joined row: the transaction and its current state, repeated once per entry. */
    private record Row(
            UUID transactionId,
            LocalDate occurredOn,
            String description,
            StoredState state,
            UUID accountId,
            io.github.naimelijah.finassist.ledger.shared.Money amount) {

        static final org.springframework.jdbc.core.RowMapper<Row> MAPPER = Row::map;

        static Row map(ResultSet row, int rowNumber) throws SQLException {
            UUID transactionId = row.getObject("transaction_id", UUID.class);
            return new Row(
                    transactionId,
                    row.getObject("occurred_on", LocalDate.class),
                    row.getString("description"),
                    new StoredState(
                            row.getString("state"),
                            row.getObject("effective_on", LocalDate.class),
                            row.getString("reason")),
                    row.getObject("account_id", UUID.class),
                    AccountingRows.money(
                            row,
                            ENTRY_TABLE,
                            "transaction_id=" + transactionId + ", ordinal=" + row.getInt("ordinal"),
                            "amount",
                            "currency"));
        }
    }

    /** A state and where it sits in the transaction's history. */
    private record Sequenced(int sequence, StoredState state) {}

    /**
     * The sealed {@link TransactionState} as it is stored: a discriminator, an optional date and an
     * optional reason, and nothing else.
     *
     * <p>Deliberately says nothing about position. Two rows are the same <em>state</em> when they
     * say the same thing, which is what decides whether a transition happened, so the record's
     * generated equality is exactly right and {@link Sequenced} carries the ordering separately.
     */
    private record StoredState(String state, LocalDate effectiveOn, String reason) {

        static StoredState of(TransactionState state) {
            return switch (state) {
                case TransactionState.Pending ignored -> new StoredState("PENDING", null, null);
                case TransactionState.Cleared cleared -> new StoredState("CLEARED", cleared.clearedOn(), null);
                case TransactionState.Reconciled reconciled ->
                    new StoredState("RECONCILED", reconciled.reconciledOn(), null);
                case TransactionState.Voided voided -> new StoredState("VOIDED", voided.voidedOn(), voided.reason());
            };
        }

        TransactionState toDomain() {
            return switch (state) {
                case "PENDING" -> new TransactionState.Pending();
                case "CLEARED" -> new TransactionState.Cleared(effectiveOn);
                case "RECONCILED" -> new TransactionState.Reconciled(effectiveOn);
                case "VOIDED" -> new TransactionState.Voided(effectiveOn, reason);
                default -> throw AccountingRows.unmappable(STATE_TABLE, "state", state);
            };
        }
    }
}
