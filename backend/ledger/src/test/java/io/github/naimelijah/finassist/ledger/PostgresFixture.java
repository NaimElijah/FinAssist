package io.github.naimelijah.finassist.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL, on the image {@code docker-compose.yml} pins, for every {@code *IT} to share.
 *
 * <p>Docker is required and its absence fails the build rather than skipping (ADR-025). A suite that
 * goes green having exercised none of the SQL is worse than one that refuses to run: the triggers,
 * the unique index and the {@code CHECK} constraints are the part of the persistence layer that does
 * not exist in Java, and nothing but a database can answer whether they work.
 *
 * <p>One container for the whole run, started once and never stopped — Ryuk removes it when the JVM
 * exits. A container per class would pay the start-up cost once per {@code *IT}, and the schema is
 * cheap to empty.
 */
public abstract class PostgresFixture {

    /**
     * Pinned to the same tag as Compose, so what CI proves is what a developer runs. Started in a
     * static initialiser rather than through {@code @Container}, which would start one per class.
     */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-trixie");

    static {
        POSTGRES.start();
    }

    /**
     * Every table, in one statement.
     *
     * <p>{@code TRUNCATE} rather than {@code DELETE} for a reason beyond speed: the journal's
     * append-only triggers refuse a {@code DELETE} outright, and correctly so. Row triggers do not
     * fire for {@code TRUNCATE}, and it needs an ownership privilege the eventual production role
     * will not hold, which is what makes it a safe hole to leave open (ADR-024).
     *
     * <p>{@code flyway_schema_history} is deliberately absent: emptying it would make Flyway re-run
     * every migration into a schema that already has it.
     */
    private static final String EMPTY_EVERY_TABLE = """
            TRUNCATE accounting.journal_entry,
                     accounting.journal_transaction_state,
                     accounting.journal_transaction,
                     accounting.account,
                     budgeting.budget_limit,
                     budgeting.budget,
                     budgeting.categorisation_rule,
                     planning.scenario_flow,
                     planning.scenario,
                     planning.planned_flow
            RESTART IDENTITY CASCADE
            """;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void emptyEveryTable() {
        jdbc.sql(EMPTY_EVERY_TABLE).update();
    }

    /** For the few assertions that are about rows rather than aggregates. */
    protected JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * A second, independent connection to the same database, owned by nothing in this JVM.
     *
     * <p>The only honest way to ask "did that survive leaving the process?" without restarting one:
     * a client with its own connection, no pool in common and no shared cache, reading what another
     * wrote.
     */
    protected static JdbcClient freshConnection() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName(POSTGRES.getDriverClassName());
        return JdbcClient.create(source);
    }
}
