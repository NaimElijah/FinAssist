package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Context isolation, asserted where ArchUnit cannot reach.
 *
 * <p>{@code LedgerArchitectureTest.contexts_do_not_depend_on_each_other} reads bytecode, so it stops
 * at the edge of the JVM. A migration adding {@code REFERENCES accounting.account} from a planning
 * table would leave every Java rule green and still weld two contexts together — permanently, since
 * migrations are append-only once released and a foreign key is the hardest kind of coupling to
 * remove.
 *
 * <p>This is the counterpart on the other side of the boundary (ADR-024).
 *
 * <p>The join below carries the catalog and schema as well as the name, because a constraint name
 * is unique per schema and not per database. Matching on the name alone would let two contexts
 * that happened to name a constraint the same thing produce a cross product, and this test would
 * then report a crossing that does not exist - or bury a real one in the noise.
 */
@SpringBootTest
class SchemaBoundariesIT extends PostgresFixture {

    private static final List<String> CONTEXT_SCHEMAS = List.of("accounting", "budgeting", "planning");

    /**
     * Every column of a relation that stores rows, in every schema PostgreSQL does not own itself: an
     * ordinary table ({@code r}), a partitioned one ({@code p}) or a materialized view ({@code m}), as
     * {@code :relationKinds} admits, but never a plain view, which stores nothing. Read from {@code
     * pg_attribute} rather than {@code information_schema.columns}, which lists a view's columns and
     * omits a materialized view's.
     */
    private static final String STORED_COLUMNS = """
            SELECT namespace.nspname || '.' || relation.relname || '.' || attribute.attname
              FROM pg_attribute attribute
              JOIN pg_class relation ON relation.oid = attribute.attrelid
              JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
             WHERE relation.relkind IN (:relationKinds)
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
               AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
               AND namespace.nspname NOT LIKE 'pg\\_%'
            """;

    private static final List<String> STORING_RELATIONS = List.of("r", "p", "m");

    /**
     * The relations a {@code CHECK} can be declared on. A materialized view takes none, so a currency
     * column in one could never satisfy the currency rule; its currency is its source table's, which
     * is checked.
     */
    private static final List<String> CHECKABLE_RELATIONS = List.of("r", "p");

    /** A column holding a currency code: {@code currency} itself, or a qualified one such as {@code cost_currency}. */
    private static final String CURRENCY_COLUMN = "(^|_)currency$";

    @Test
    void Given_the_migrated_schema_When_its_foreign_keys_are_read_Then_none_of_them_crosses_a_schema() {
        List<String> crossing = jdbc().sql("""
                        SELECT source.constraint_name || ': ' || source.table_schema || ' -> ' || target.table_schema
                          FROM information_schema.table_constraints source
                          JOIN information_schema.constraint_column_usage target
                            ON target.constraint_catalog = source.constraint_catalog
                           AND target.constraint_schema = source.constraint_schema
                           AND target.constraint_name = source.constraint_name
                         WHERE source.constraint_type = 'FOREIGN KEY'
                           AND source.table_schema <> target.table_schema
                        """).query(String.class).list();

        assertThat(crossing)
                .as("a foreign key crossing a schema welds two bounded contexts together")
                .isEmpty();
    }

    /**
     * Derived, never stored (root {@code CLAUDE.md}, "The domain"), at the storage layer: a balance is
     * summed from journal entries, a budget's spend and remainder computed from the journal, a
     * projection computed from its scenario. A column holding one would be a second copy free to
     * drift from the facts, and a materialized view is such a copy too. {@code
     * LedgerArchitectureTest.derived_quantities_are_never_stored} is the same rule over the domain's
     * fields, by the same names: {@link LedgerArchitectureTest#DERIVED_QUANTITIES}.
     */
    @Test
    void Given_the_migrated_schema_When_its_columns_are_read_Then_none_stores_a_derived_quantity() {
        List<String> stored = jdbc().sql(STORED_COLUMNS + "AND attribute.attname ~* :derivedQuantity")
                .param("relationKinds", STORING_RELATIONS)
                .param("derivedQuantity", "(" + LedgerArchitectureTest.DERIVED_QUANTITIES + ")")
                .query(String.class)
                .list();

        assertThat(stored)
                .as("a balance, spend, remainder or projection is derived on every read, never stored")
                .isEmpty();
    }

    /**
     * The currency table is written three times over: {@link Currency} in Java, {@code test_currency.py}'s
     * in Python (held together by {@code MoneyVectorsTest}), and a hand-written {@code CHECK (currency IN
     * (...))} on every table that stores one. This holds the third to the first: every currency column in
     * every migrated schema is constrained by a check, tied to its column through {@code
     * pg_constraint.conkey} rather than found by its text, and every check on a currency column names
     * exactly the currencies {@link Currency} declares, no fewer and no more. Each check is keyed by its
     * own name, so a table with two is held to both. A currency added on one side is refused by this
     * test rather than by the owner's first write in it.
     */
    @Test
    void Given_the_migrated_schema_When_its_currency_checks_are_read_Then_each_names_exactly_the_currency_table() {
        Set<String> currencyColumns = new TreeSet<>(jdbc().sql(STORED_COLUMNS + "AND attribute.attname ~ :currency")
                .param("relationKinds", CHECKABLE_RELATIONS)
                .param("currency", CURRENCY_COLUMN)
                .query(String.class)
                .list());
        List<CurrencyCheck> checks = jdbc().sql("""
                        SELECT namespace.nspname || '.' || relation.relname || '.' || check_constraint.conname
                                   AS check_name,
                               namespace.nspname || '.' || relation.relname || '.' || attribute.attname
                                   AS column_name,
                               pg_get_constraintdef(check_constraint.oid) AS definition
                          FROM pg_constraint check_constraint
                          JOIN pg_class relation ON relation.oid = check_constraint.conrelid
                          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                          JOIN pg_attribute attribute
                            ON attribute.attrelid = check_constraint.conrelid
                           AND attribute.attnum = ANY (check_constraint.conkey)
                         WHERE check_constraint.contype = 'c'
                           AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
                           AND namespace.nspname NOT LIKE 'pg\\_%'
                           AND attribute.attname ~ :currency
                        """)
                .param("currency", CURRENCY_COLUMN)
                .query((row, rowNumber) -> new CurrencyCheck(
                        row.getString("check_name"),
                        row.getString("column_name"),
                        quotedCodes(row.getString("definition"))))
                .list();
        Set<String> uncheckedColumns = new TreeSet<>(currencyColumns);
        checks.forEach(check -> uncheckedColumns.remove(check.column()));
        Map<String, Set<String>> codesByCheck = checks.stream()
                .collect(Collectors.toMap(
                        CurrencyCheck::name, CurrencyCheck::codes, (same, again) -> same, TreeMap::new));
        Set<String> currencyTable =
                Arrays.stream(Currency.values()).map(Currency::name).collect(Collectors.toCollection(TreeSet::new));

        assertThat(currencyColumns)
                .as("a scan finding no currency column proves nothing")
                .isNotEmpty();
        assertThat(uncheckedColumns).as("currency columns no check constrains").isEmpty();
        assertThat(codesByCheck)
                .as("each check on a currency column names exactly the currencies Currency declares")
                .allSatisfy((check, codes) -> assertThat(codes).as(check).isEqualTo(currencyTable));
    }

    /** One {@code CHECK} constraint, one currency column it reads, and the codes its text quotes. */
    private record CurrencyCheck(String name, String column, Set<String> codes) {}

    private static Set<String> quotedCodes(String checkDefinition) {
        return Pattern.compile("'([A-Z]{3})'")
                .matcher(checkDefinition)
                .results()
                .map(match -> match.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every context owns a schema, so a missing one means a migration did not run. */
    @Test
    void Given_the_migrated_database_When_its_schemas_are_read_Then_every_context_owns_one() {
        List<String> schemas = jdbc().sql(
                        "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN (:names)")
                .param("names", CONTEXT_SCHEMAS)
                .query(String.class)
                .list();

        assertThat(schemas).containsExactlyInAnyOrderElementsOf(CONTEXT_SCHEMAS);
    }

    /**
     * Every context's migrations ran, in one history.
     *
     * <p>Asserted by major version rather than by an exact list, so a legitimate {@code V1_2} does not
     * fail this test. What it catches is the failure the per-context layout could actually produce: a
     * version collision between two directories, which Flyway resolves by running one and leaving the
     * other absent rather than by raising an error.
     */
    @Test
    void Given_the_migrations_When_the_history_is_read_Then_every_context_contributed_one() {
        List<String> majors = jdbc().sql(
                        "SELECT DISTINCT split_part(version, '.', 1) FROM flyway_schema_history WHERE success")
                .query(String.class)
                .list();

        assertThat(majors)
                .as("one major version per context: 1 accounting, 2 budgeting, 3 planning")
                .containsExactlyInAnyOrder("1", "2", "3");
    }
}
