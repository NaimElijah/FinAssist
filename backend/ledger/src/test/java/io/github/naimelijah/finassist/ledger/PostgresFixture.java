package io.github.naimelijah.finassist.ledger;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
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
     * Every table the migrations created, read from the catalog on each reset rather than listed by
     * hand: a table a new migration adds is emptied between tests from the moment it exists, and so is
     * every table in a schema a new context adds, where a hand-kept list of either would leak its rows
     * from one test into the next until someone remembered it.
     *
     * <p>Every schema PostgreSQL does not own itself is read: {@code pg_catalog}, {@code
     * information_schema}, and the {@code pg_}-prefixed ones such as {@code pg_toast} are not. Flyway
     * keeps {@code flyway_schema_history} in {@code public}, and emptying it would make Flyway re-run
     * every migration into a schema that already has it, so it is excluded by name wherever it lives.
     */
    private static final String EVERY_MIGRATED_TABLE = """
            SELECT format('%I.%I', table_schema, table_name)
              FROM information_schema.tables
             WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
               AND table_schema NOT LIKE 'pg\\_%'
               AND table_type = 'BASE TABLE'
               AND table_name <> 'flyway_schema_history'
             ORDER BY 1
            """;

    @Autowired
    private JdbcClient jdbc;

    /**
     * Replaces {@code LedgerSecurityConfiguration}'s {@code Clock.systemUTC()} for every {@code *IT}
     * that extends this class, built by {@link #clock()}: the token validators read this bean, and
     * {@link TestTokens} signs relative to the same {@link TestTokens#NOW}, so a token's lifetime is
     * judged against the instant it was issued for rather than against however long the fork has run.
     * One override for every subclass keeps them on one cached context.
     */
    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TestTokens.NOW, ZoneOffset.UTC);
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * A subclass-settable override, read lazily by {@link #jwks}'s supplier every time the property
     * is resolved. {@code @DynamicPropertySource} methods are collected by walking from the most
     * specific class up through its superclasses ({@code MethodIntrospector.selectMethods} via
     * {@code ReflectionUtils.doWithMethods}), so a subclass's own method for the <em>same</em>
     * property key is invoked before, and therefore overwritten by, this class's — registering
     * under the same key a second time is not the override mechanism it looks like. Routing the
     * value through this field instead lets {@link LedgerKeySourceOutageIT} change what {@link
     * #jwks} resolves to without depending on registration order.
     */
    private static volatile String jwksUriOverride;

    /**
     * Points {@code LedgerSecurityConfiguration}'s {@code JwtDecoder} at {@link TestJwks} instead of
     * the real Keycloak {@code application.yml} defaults to, so every {@code *IT} that extends this
     * class can send a real signed {@code TestTokens} token and have it genuinely verified — unless
     * {@link #jwksUriOverride} names somewhere else.
     */
    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add(
                "finassist.security.jwks-uri",
                () -> jwksUriOverride != null
                        ? jwksUriOverride
                        : TestJwks.instance().jwksUri());
    }

    /**
     * Lets {@link LedgerKeySourceOutageIT} redirect the JWKS URI for the length of its own run.
     * {@code jwksUriOverride} is a static field shared by every {@link PostgresFixture} subclass, and
     * Surefire and Failsafe both reuse one JVM fork across test classes by default, so a caller must
     * pair this with {@link #restoreJwksUri} in an {@code @AfterAll} — an override left set would
     * point every later {@code *IT} in the same fork at a JWKS source that no longer exists.
     */
    protected static void overrideJwksUri(String uri) {
        jwksUriOverride = uri;
    }

    protected static void restoreJwksUri() {
        jwksUriOverride = null;
    }

    /**
     * Every table, in one statement.
     *
     * <p>{@code TRUNCATE} rather than {@code DELETE} for a reason beyond speed: the journal's
     * append-only triggers refuse a {@code DELETE} outright, and correctly so. Row triggers do not
     * fire for {@code TRUNCATE}, and it needs an ownership privilege the eventual production role
     * will not hold, which is what makes it a safe hole to leave open (ADR-024).
     *
     * <p>No {@code CASCADE}: every table is named, so a foreign key between two of them is satisfied
     * by emptying both at once, and a table holding a reference that the query above missed makes the
     * statement fail rather than being emptied unseen. {@code RESTART IDENTITY} stays, because {@code
     * categorisation_rule.ordinal} is an identity whose values a test reads.
     *
     * <p>The statement is assembled from the catalog's own names, each already quoted by {@code
     * format('%I')}; no identifier here comes from outside the database.
     */
    @BeforeEach
    void emptyEveryTable() {
        List<String> tables = jdbc.sql(EVERY_MIGRATED_TABLE).query(String.class).list();
        if (tables.isEmpty()) {
            throw new IllegalStateException("no migrated table exists to empty; the migrations did not run");
        }
        jdbc.sql("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY").update();
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

    /**
     * Creates a brand-new, empty database inside this same container, for the two tests that must run
     * Flyway themselves and cannot share the schema every other {@code *IT} truncates between cases
     * ({@code JournalActorMigrationIT} and {@link LedgerUpgradeIT}).
     *
     * <p>{@code CREATE DATABASE} takes no bind parameter for its own name — PostgreSQL supports none
     * for an identifier — so the name is interpolated rather than bound. Every caller passes a literal
     * it wrote itself; this is a test fixture building its own throwaway database, not a boundary that
     * ever sees external input.
     *
     * <p>Declared as the concrete {@link DriverManagerDataSource} rather than {@link DataSource}
     * because {@link LedgerUpgradeIT} has to hand the same URL and credentials to a Spring context
     * through {@code @DynamicPropertySource}, and {@link DataSource} exposes none of the three.
     *
     * @param name a lowercase identifier, unique for the run
     */
    protected static DriverManagerDataSource freshDatabase(String name) {
        freshConnection().sql("CREATE DATABASE " + name).update();
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        DriverManagerDataSource source =
                new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName(POSTGRES.getDriverClassName());
        return source;
    }
}
