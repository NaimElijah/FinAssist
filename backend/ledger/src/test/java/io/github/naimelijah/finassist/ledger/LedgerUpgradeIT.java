package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The ledger must start against the database the owner already has, not only against an empty one.
 *
 * <p>Every other {@code *IT} — and every Compose run, and every smoke check — begins on a volume
 * with nothing in it, where Flyway applies every script in version order and nothing can be out of
 * order. A real database is not in that state. {@code V1_1}, {@code V2_1} and {@code V3_1} all
 * entered git in one commit, so the owner's history is {@code 1.1}, {@code 2.1}, {@code 3.1}, and
 * {@code V1_2} and every accounting script after it arrive <em>below</em> the highest version
 * already applied. Flyway classes such a migration {@code IGNORED} and refuses to start unless it is
 * configured to run out of order (ADR-035), which is the one thing no fresh-volume test can notice.
 *
 * <p>So this test rebuilds that history literally: the three committed scripts, copied byte for byte
 * out of the classpath into a temporary directory and applied by a plain Flyway with no flag set,
 * then a journal transaction seeded the way the pre-{@code V1_2} repository wrote one. Only then is
 * the ledger's own application context started against that database, which is what runs the
 * ledger's own {@code spring.flyway.*} configuration rather than a hand-built copy of it. A context
 * that fails to start is the owner's ledger failing to start.
 *
 * <p>The scripts are copied rather than read from {@code classpath:db/migration} with a {@code
 * target} because the checksums Flyway records have to be the committed files' own: the owner's
 * database already holds them, and a checksum that differed by one byte would fail validation for a
 * reason that has nothing to do with what this test is about.
 */
@SpringBootTest
class LedgerUpgradeIT {

    private static final String DATABASE = "ledger_upgrade_it";

    /**
     * Exactly what the owner's {@code flyway_schema_history} holds before this branch: the three
     * schema migrations that entered git together, and not {@code V1_2}.
     */
    private static final List<String> ALREADY_APPLIED = List.of(
            "db/migration/accounting/V1_1__accounting_schema.sql",
            "db/migration/budgeting/V2_1__budgeting_schema.sql",
            "db/migration/planning/V3_1__planning_schema.sql");

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);

    private static final UUID HISTORICAL_TRANSACTION = UUID.randomUUID();

    /**
     * Built while this class is initialised, which is before Spring invokes {@link
     * #pointTheLedgerAtTheOwnersDatabase} and therefore before the context — and its Flyway — exists.
     * The order matters: the database has to already be the owner's, rows and all, by the time the
     * ledger is asked to start against it.
     */
    private static final DriverManagerDataSource OWNERS_DATABASE = ownersDatabaseBeforeTheUpgrade();

    @DynamicPropertySource
    static void pointTheLedgerAtTheOwnersDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OWNERS_DATABASE::getUrl);
        registry.add("spring.datasource.username", OWNERS_DATABASE::getUsername);
        registry.add("spring.datasource.password", OWNERS_DATABASE::getPassword);
    }

    @Autowired
    private JdbcClient jdbc;

    /**
     * {@code installed_rank} order rather than version order on purpose: it records the order the
     * migrations were actually run in, so this asserts that every accounting migration released
     * since {@code 1.1} ran last, in version order, after a version higher than itself — the
     * out-of-order application the owner's database needs and a fresh one never performs.
     */
    @Test
    void
            Given_a_database_already_at_1_1_2_1_and_3_1_When_the_ledger_starts_Then_the_later_accounting_migrations_are_applied_after_them() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                        .query(String.class)
                        .list())
                .containsExactly("1.1", "2.1", "3.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7");
    }

    @Test
    void Given_a_database_already_at_1_1_2_1_and_3_1_When_the_ledger_starts_Then_both_recorded_by_columns_exist() {
        assertThat(jdbc.sql("""
                        SELECT table_name || '.' || column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'accounting' AND column_name = 'recorded_by'
                        ORDER BY table_name
                        """).query(String.class).list())
                .containsExactly("journal_transaction.recorded_by", "journal_transaction_state.recorded_by");
    }

    /**
     * {@code convalidated} false is the whole point of ADR-033's {@code NOT VALID}: the historical
     * rows below are never scanned against the constraint, while every new row still is. A
     * constraint that arrived validated would mean the migration had scanned — and so could have
     * failed on — rows nobody can correct.
     */
    @Test
    void Given_a_database_already_at_1_1_2_1_and_3_1_When_the_ledger_starts_Then_both_checks_stay_unvalidated() {
        assertThat(jdbc.sql("""
                        SELECT conname
                        FROM pg_constraint
                        WHERE connamespace = 'accounting'::regnamespace
                          AND contype = 'c'
                          AND NOT convalidated
                        ORDER BY conname
                        """).query(String.class).list())
                .containsExactly(
                        "journal_transaction_recorded_by_shape", "journal_transaction_state_recorded_by_shape");
    }

    /**
     * The owner's database may already hold an entry in a currency its account no longer has: the
     * account adapter's upsert rewrote a currency on a re-save until {@code 1.6} refused it. {@code
     * 1.6}'s foreign key is {@code NOT VALID} so that it is added without scanning such rows; the
     * context starting at all is the proof it applied, and {@code convalidated} false is the proof it
     * never scanned.
     */
    @Test
    void
            Given_an_entry_whose_account_changed_currency_before_1_6_When_the_ledger_starts_Then_the_foreign_key_stays_unvalidated() {
        assertThat(jdbc.sql("""
                        SELECT convalidated
                        FROM pg_constraint
                        WHERE connamespace = 'accounting'::regnamespace
                          AND conname = 'journal_entry_in_account_currency'
                        """).query(Boolean.class).list()).containsExactly(false);
    }

    @Test
    void Given_a_journal_row_written_before_1_2_When_the_ledger_has_started_Then_it_still_names_no_recorder() {
        assertThat(jdbc.sql("SELECT recorded_by FROM accounting.journal_transaction WHERE id = :id")
                        .param("id", HISTORICAL_TRANSACTION)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(jdbc.sql("SELECT recorded_by FROM accounting.journal_transaction_state WHERE transaction_id = :id")
                        .param("id", HISTORICAL_TRANSACTION)
                        .query(String.class)
                        .list())
                .containsExactly((String) null);
    }

    private static DriverManagerDataSource ownersDatabaseBeforeTheUpgrade() {
        DriverManagerDataSource database = PostgresFixture.freshDatabase(DATABASE);
        Flyway.configure()
                .dataSource(database)
                .locations("filesystem:" + theCommittedScriptsTheOwnerHasApplied())
                .load()
                .migrate();
        seedTheTransactionThePreV1_2CodeWrote(JdbcClient.create(database));
        seedAnAccountWhoseCurrencyChangedUnderItsEntries(JdbcClient.create(database));
        return database;
    }

    /**
     * Copies the three applied scripts, unaltered, into a directory of their own, so a plain Flyway
     * run resolves those three and nothing else. Reading {@code classpath:db/migration} with a
     * {@code target} cannot express this history: {@code V1_2} sits between {@code 1.1} and {@code
     * 2.1} in version order, so any target high enough to reach {@code 3.1} would apply it too.
     */
    private static Path theCommittedScriptsTheOwnerHasApplied() {
        try {
            Path directory = Files.createTempDirectory("finassist-owners-history");
            directory.toFile().deleteOnExit();
            for (String script : ALREADY_APPLIED) {
                Path copy = directory.resolve(script.substring(script.lastIndexOf('/') + 1));
                try (InputStream committed =
                        LedgerUpgradeIT.class.getClassLoader().getResourceAsStream(script)) {
                    if (committed == null) {
                        throw new IllegalStateException("migration missing from the classpath: " + script);
                    }
                    Files.copy(committed, copy, StandardCopyOption.REPLACE_EXISTING);
                }
                copy.toFile().deleteOnExit();
            }
            return directory.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the owner's applied migrations", e);
        }
    }

    /** The shape the repository wrote before {@code V1_2}: no {@code recorded_by} column to write to. */
    private static void seedTheTransactionThePreV1_2CodeWrote(JdbcClient jdbc) {
        UUID cash = seedAccount(jdbc, "Cash", "ASSET");
        UUID groceries = seedAccount(jdbc, "Groceries", "EXPENSE");
        jdbc.sql("INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency)"
                        + " VALUES (:id, :on, 'Groceries', 'ILS')")
                .param("id", HISTORICAL_TRANSACTION)
                .param("on", MARCH_1)
                .update();
        jdbc.sql("""
                        INSERT INTO accounting.journal_entry VALUES
                            (:id, 0, :groceries, 12.50, 'ILS'),
                            (:id, 1, :cash, -12.50, 'ILS')
                        """)
                .param("id", HISTORICAL_TRANSACTION)
                .param("groceries", groceries)
                .param("cash", cash)
                .update();
        jdbc.sql("INSERT INTO accounting.journal_transaction_state (transaction_id, sequence, state, effective_on,"
                        + " reason) VALUES (:id, 0, 'PENDING', NULL, NULL)")
                .param("id", HISTORICAL_TRANSACTION)
                .update();
    }

    /**
     * A transaction posted to a wallet in ILS, after which the wallet was re-saved in USD: the state
     * the account adapter's upsert could leave, since nothing held an account's currency to its
     * entries before {@code 1.6}.
     */
    private static void seedAnAccountWhoseCurrencyChangedUnderItsEntries(JdbcClient jdbc) {
        UUID wallet = seedAccount(jdbc, "Wallet", "ASSET");
        UUID travel = seedAccount(jdbc, "Travel", "EXPENSE");
        UUID transaction = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounting.journal_transaction (id, occurred_on, description, currency)"
                        + " VALUES (:id, :on, 'Train', 'ILS')")
                .param("id", transaction)
                .param("on", MARCH_1)
                .update();
        jdbc.sql("""
                        INSERT INTO accounting.journal_entry VALUES
                            (:id, 0, :travel, 30.00, 'ILS'),
                            (:id, 1, :wallet, -30.00, 'ILS')
                        """)
                .param("id", transaction)
                .param("travel", travel)
                .param("wallet", wallet)
                .update();
        jdbc.sql("INSERT INTO accounting.journal_transaction_state (transaction_id, sequence, state, effective_on,"
                        + " reason) VALUES (:id, 0, 'PENDING', NULL, NULL)")
                .param("id", transaction)
                .update();
        jdbc.sql("UPDATE accounting.account SET currency = 'USD' WHERE id = :id")
                .param("id", wallet)
                .update();
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
}
