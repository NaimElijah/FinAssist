package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What {@code V3_3} does to a database holding a growth rate it refuses: one an earlier ledger
 * accepted, above 1 or past ten decimal places (ADR-072).
 *
 * <p>{@code V3_3} adds its checks validated, so it scans the rates already stored, and the claims
 * {@code docs/operations/operations.md} makes about a refused upgrade rest on that scan: the
 * migration fails naming the constraint and no value, every migration before it stays applied, and
 * once the row is corrected only {@code V3_3} runs. This builds its own database in the shared
 * container ({@link PostgresFixture#freshDatabase}), migrates it to {@code 3.2}, stores rates the
 * way an earlier ledger could, and migrates it as the ledger does: every location, out of order.
 */
@SpringBootTest
class ScenarioGrowthMigrationIT extends PostgresFixture {

    private static final String LOCATIONS = "classpath:db/migration";
    private static final String DATABASE = "scenario_growth_migration_it";

    /** A rate above 1, written to a precision no message should repeat. */
    private static final String RATE_ABOVE_ONE = "3.1415926535";

    @Test
    void
            Given_a_stored_rate_v3_3_refuses_When_migrated_Then_it_fails_naming_the_check_and_only_3_3_runs_once_the_rate_is_corrected() {
        DataSource database = freshDatabase(DATABASE);
        Flyway.configure()
                .dataSource(database)
                .locations(LOCATIONS)
                .outOfOrder(true)
                .target("3.2")
                .load()
                .migrate();
        JdbcClient jdbc = JdbcClient.create(database);
        UUID typedAsAPercentage = UUID.randomUUID();
        jdbc.sql("INSERT INTO planning.scenario VALUES (:id, 'Typed as a percentage', 'ILS', CAST(:rate AS numeric))")
                .param("id", typedAsAPercentage)
                .param("rate", RATE_ABOVE_ONE)
                .update();
        Flyway likeTheLedger = Flyway.configure()
                .dataSource(database)
                .locations(LOCATIONS)
                .outOfOrder(true)
                .load();

        assertThatThrownBy(likeTheLedger::migrate)
                .hasMessageContaining("scenario_growth_at_most_one")
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .doesNotContain(RATE_ABOVE_ONE)
                        .doesNotContain("Typed as a percentage"));
        assertThat(appliedVersions(jdbc)).contains("3.2").doesNotContain("3.3");

        jdbc.sql("UPDATE planning.scenario SET growth_per_step = 0.05 WHERE id = :id")
                .param("id", typedAsAPercentage)
                .update();
        MigrateResult corrected = likeTheLedger.migrate();

        assertThat(corrected.migrations)
                .extracting(migration -> migration.version)
                .containsExactly("3.3");
        assertThat(appliedVersions(jdbc)).contains("3.3");
    }

    private static List<String> appliedVersions(JdbcClient jdbc) {
        return jdbc.sql("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL")
                .query(String.class)
                .list();
    }
}
