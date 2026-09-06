package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 */
@SpringBootTest
class SchemaBoundariesIT extends PostgresFixture {

    private static final List<String> CONTEXT_SCHEMAS = List.of("accounting", "budgeting", "planning");

    @Test
    void Given_the_migrated_schema_When_its_foreign_keys_are_read_Then_none_of_them_crosses_a_schema() {
        List<String> crossing = jdbc().sql("""
                        SELECT source.constraint_name || ': ' || source.table_schema || ' -> ' || target.table_schema
                          FROM information_schema.table_constraints source
                          JOIN information_schema.constraint_column_usage target
                            ON target.constraint_name = source.constraint_name
                         WHERE source.constraint_type = 'FOREIGN KEY'
                           AND source.table_schema <> target.table_schema
                        """).query(String.class).list();

        assertThat(crossing)
                .as("a foreign key crossing a schema welds two bounded contexts together")
                .isEmpty();
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
