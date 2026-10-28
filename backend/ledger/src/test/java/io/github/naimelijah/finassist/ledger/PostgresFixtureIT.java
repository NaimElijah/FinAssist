package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The isolation every other {@code *IT} relies on: {@link PostgresFixture} empties every table the
 * contexts own between tests, including one no migration had created when the fixture was written.
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
}
