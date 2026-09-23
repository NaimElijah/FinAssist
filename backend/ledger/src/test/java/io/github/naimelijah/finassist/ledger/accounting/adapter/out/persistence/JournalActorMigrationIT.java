package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What {@code V1_2} does to accounting rows that already exist: it adds columns, leaves every
 * historical row saying nobody recorded it, and makes a recorder mandatory from then on (ADR-033).
 *
 * <p>Every other {@code *IT} runs against a schema Flyway has already brought to {@code latest}
 * before the first test method executes, so none of them can tell an additive migration apart from
 * one that silently rewrote history. This test builds its own database inside the same shared
 * container ({@link PostgresFixture#freshDatabase}), migrates it only to {@code 1.1} — the schema
 * every row in the owner's real ledger was written under — inserts a transaction the way the
 * pre-{@code V1_2} code did, with no {@code recorded_by} column to write to, and only then migrates
 * to {@code latest}. If {@code V1_2} could not run against real rows, or if it touched a row it has
 * no business touching, this is the one place that would show up: nowhere else runs the migration
 * itself against seeded data and then writes through the new constraint.
 *
 * <p><b>This is not a rehearsal of the owner's real start-up, and does not claim to be.</b> No real
 * database has ever stood at {@code 1.1} alone: {@code V1_1}, {@code V2_1} and {@code V3_1} entered
 * git in one commit. Migrating {@code 1.1 → 1.2 → 2.1 → 3.1}, as the {@code latest} run below does,
 * is an order no real database will see, and it is exactly the order that hides whether Flyway would
 * accept {@code 1.2} arriving below a version already applied. {@link
 * io.github.naimelijah.finassist.ledger.LedgerUpgradeIT} is what covers that, by rebuilding the
 * applied history literally and starting the ledger's own context against it (ADR-035). The two are
 * kept apart because they answer different questions: this one, whether the migration's SQL is safe
 * for existing rows; that one, whether the ledger starts at all.
 */
@SpringBootTest
class JournalActorMigrationIT extends PostgresFixture {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final String LOCATIONS = "classpath:db/migration";
    private static final String DATABASE = "journal_actor_migration_it";

    @Test
    void
            Given_rows_written_before_V1_2_When_migrated_to_latest_Then_history_keeps_no_recorder_and_a_new_row_must_name_one() {
        DataSource database = freshDatabase(DATABASE);
        Flyway.configure()
                .dataSource(database)
                .locations(LOCATIONS)
                .target("1.1")
                .load()
                .migrate();
        JdbcClient jdbc = JdbcClient.create(database);
        UUID cash = seedAccount(jdbc, "Cash", "ASSET");
        UUID groceries = seedAccount(jdbc, "Groceries", "EXPENSE");
        UUID historicalId = UUID.randomUUID();
        seedTransactionWithNoRecordedByColumn(jdbc, historicalId, cash, groceries);

        Flyway.configure().dataSource(database).locations(LOCATIONS).load().migrate();

        assertThat(jdbc.sql("SELECT description FROM accounting.journal_transaction WHERE id = :id")
                        .param("id", historicalId)
                        .query(String.class)
                        .single())
                .isEqualTo("Groceries");
        assertThat(jdbc.sql("SELECT recorded_by FROM accounting.journal_transaction WHERE id = :id")
                        .param("id", historicalId)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(jdbc.sql("SELECT recorded_by FROM accounting.journal_transaction_state"
                                + " WHERE transaction_id = :id")
                        .param("id", historicalId)
                        .query(String.class)
                        .list())
                .containsExactly((String) null);

        UUID unrecorded = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency)"
                                        + " VALUES (:id, :on, 'Unrecorded', 'ILS')")
                        .param("id", unrecorded)
                        .param("on", MARCH_1)
                        .update())
                .hasMessageContaining("journal_transaction_recorded_by_shape");

        UUID afterUpgrade = UUID.randomUUID();
        seedTransactionWithRecordedBy(jdbc, afterUpgrade, cash, groceries, "owner-user");
        assertThat(jdbc.sql("SELECT recorded_by FROM accounting.journal_transaction WHERE id = :id")
                        .param("id", afterUpgrade)
                        .query(String.class)
                        .single())
                .isEqualTo("owner-user");
    }

    private static UUID seedAccount(JdbcClient jdbc, String name, String type) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounting.account (id, name, normalised_name, type, currency)"
                        + " VALUES (:id, :name, :normalised, :type, 'ILS')")
                .param("id", id)
                .param("name", name)
                .param("normalised", name.toLowerCase(Locale.ROOT))
                .param("type", type)
                .update();
        return id;
    }

    /** Exactly the shape the repository wrote before this migration: no {@code recorded_by} column at all. */
    private static void seedTransactionWithNoRecordedByColumn(JdbcClient jdbc, UUID id, UUID cash, UUID groceries) {
        jdbc.sql("INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency)"
                        + " VALUES (:id, :on, 'Groceries', 'ILS')")
                .param("id", id)
                .param("on", MARCH_1)
                .update();
        jdbc.sql("""
                        INSERT INTO accounting.journal_entry VALUES
                            (:id, 0, :groceries, 12.50, 'ILS'),
                            (:id, 1, :cash, -12.50, 'ILS')
                        """)
                .param("id", id)
                .param("groceries", groceries)
                .param("cash", cash)
                .update();
        jdbc.sql("INSERT INTO accounting.journal_transaction_state (transaction_id, sequence, state, effective_on,"
                        + " reason) VALUES (:id, 0, 'PENDING', NULL, NULL)")
                .param("id", id)
                .update();
    }

    /** The post-{@code V1_2} shape: a balanced transaction whose rows each name a recorder. */
    private static void seedTransactionWithRecordedBy(
            JdbcClient jdbc, UUID id, UUID cash, UUID groceries, String recordedBy) {
        jdbc.sql("INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency, recorded_by)"
                        + " VALUES (:id, :on, 'Recorded after the upgrade', 'ILS', :recordedBy)")
                .param("id", id)
                .param("on", MARCH_1)
                .param("recordedBy", recordedBy)
                .update();
        jdbc.sql("""
                        INSERT INTO accounting.journal_entry VALUES
                            (:id, 0, :groceries, 5.00, 'ILS'),
                            (:id, 1, :cash, -5.00, 'ILS')
                        """)
                .param("id", id)
                .param("groceries", groceries)
                .param("cash", cash)
                .update();
        jdbc.sql("INSERT INTO accounting.journal_transaction_state (transaction_id, sequence, state, effective_on,"
                        + " reason, recorded_by) VALUES (:id, 0, 'PENDING', NULL, NULL, :recordedBy)")
                .param("id", id)
                .param("recordedBy", recordedBy)
                .update();
    }
}
