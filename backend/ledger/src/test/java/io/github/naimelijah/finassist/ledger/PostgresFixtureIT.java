package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The isolation every other {@code *IT} relies on: {@link PostgresFixture} empties every table the
 * migrations created between tests, including one in a table or a schema no migration had created when
 * the fixture was written, and leaves Flyway's own history alone.
 */
@SpringBootTest
class PostgresFixtureIT extends PostgresFixture {

    /**
     * A table created here, after the fixture was written, stands for the next migration's. Its row
     * must not survive the reset, or it would leak into whichever test ran next.
     */
    @Test
    void Given_a_row_in_a_table_added_after_the_fixture_was_written_When_tables_are_emptied_Then_it_is_gone() {
        jdbc().sql("CREATE TABLE budgeting.fixture_probe (note text NOT NULL)").update();
        try {
            jdbc().sql("INSERT INTO budgeting.fixture_probe VALUES ('left behind by an earlier test')")
                    .update();

            emptyEveryTable();

            assertThat(jdbc().sql("SELECT count(*) FROM budgeting.fixture_probe")
                            .query(Integer.class)
                            .single())
                    .as("rows left in a table the reset did not know about")
                    .isZero();
        } finally {
            jdbc().sql("DROP TABLE budgeting.fixture_probe").update();
        }
    }

    /**
     * A schema created here stands for the next context's, whose migration would add one. Its rows must
     * not survive the reset either.
     */
    @Test
    void Given_a_row_in_a_schema_added_after_the_fixture_was_written_When_tables_are_emptied_Then_it_is_gone() {
        jdbc().sql("CREATE SCHEMA fixture_probe_context").update();
        try {
            jdbc().sql("CREATE TABLE fixture_probe_context.probe (note text NOT NULL)")
                    .update();
            jdbc().sql("INSERT INTO fixture_probe_context.probe VALUES ('left behind by an earlier test')")
                    .update();

            emptyEveryTable();

            assertThat(jdbc().sql("SELECT count(*) FROM fixture_probe_context.probe")
                            .query(Integer.class)
                            .single())
                    .as("rows left in a schema the reset did not know about")
                    .isZero();
        } finally {
            jdbc().sql("DROP SCHEMA fixture_probe_context CASCADE").update();
        }
    }

    /**
     * Flyway's history lives in {@code public}, which the reset now reads. Emptied, it would make
     * Flyway re-run every migration into a schema that already holds it.
     */
    @Test
    void Given_the_migration_history_When_tables_are_emptied_Then_it_keeps_its_rows() {
        emptyEveryTable();

        assertThat(jdbc().sql("SELECT count(*) FROM flyway_schema_history")
                        .query(Integer.class)
                        .single())
                .as("migrations recorded in flyway_schema_history after the reset")
                .isPositive();
    }
}
