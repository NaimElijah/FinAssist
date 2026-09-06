package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strategies, in PostgreSQL.
 *
 * <p>A scenario's flows are stored as references, never as copies. In memory a {@code Scenario}
 * holds whole {@code PlannedFlow} values, but {@code ForecastService} deliberately re-reads each one
 * from {@code PlannedFlowRepository} before projecting, because the embedded copy goes stale the
 * moment a plan is committed or cancelled. Storing the copy would make that stale value durable,
 * which is the one thing worse than holding it briefly in memory.
 */
public class JdbcScenarioRepository implements ScenarioRepository {

    private static final String UPSERT_SCENARIO = """
            INSERT INTO planning.scenario (id, name, currency, growth_per_step)
            VALUES (:id, :name, :currency, :growthPerStep)
            ON CONFLICT (id) DO UPDATE
               SET name            = EXCLUDED.name,
                   currency        = EXCLUDED.currency,
                   growth_per_step = EXCLUDED.growth_per_step
            """;

    private static final String DELETE_FLOWS = """
            DELETE FROM planning.scenario_flow WHERE scenario_id = :scenarioId
            """;

    private static final String INSERT_FLOW = """
            INSERT INTO planning.scenario_flow (scenario_id, ordinal, flow_id)
            VALUES (:scenarioId, :ordinal, :flowId)
            """;

    private static final String SELECT_SCENARIO = """
            SELECT name, currency, growth_per_step FROM planning.scenario WHERE id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcScenarioRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Scenario> findById(ScenarioId id) {
        Objects.requireNonNull(id, "id");
        return jdbc.sql(SELECT_SCENARIO)
                .param("id", id.value())
                .query((row, rowNumber) -> new Header(
                        row.getString("name"),
                        Currency.valueOf(row.getString("currency")),
                        // The constructor re-applies stripTrailingZeros, so a rate stored as 100 and
                        // read back at scale 0 normalises to the same value the caller wrote.
                        new GrowthAssumption(row.getBigDecimal("growth_per_step"))))
                .optional()
                .map(header -> new Scenario(id, header.name(), header.currency(), flowsOf(id), header.growth()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Transactional because the membership is rewritten rather than amended: a scenario left
     * holding half its flows would be a different strategy, and would project a different answer.
     */
    @Override
    @Transactional
    public void save(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        jdbc.sql(UPSERT_SCENARIO)
                .param("id", scenario.id().value())
                .param("name", scenario.name())
                .param("currency", scenario.currency().name())
                .param("growthPerStep", scenario.growth().ratePerStep())
                .update();

        jdbc.sql(DELETE_FLOWS).param("scenarioId", scenario.id().value()).update();

        List<PlannedFlow> flows = scenario.flows();
        for (int ordinal = 0; ordinal < flows.size(); ordinal++) {
            jdbc.sql(INSERT_FLOW)
                    .param("scenarioId", scenario.id().value())
                    .param("ordinal", ordinal)
                    .param("flowId", flows.get(ordinal).id().value())
                    .update();
        }
    }

    private List<PlannedFlow> flowsOf(ScenarioId id) {
        return jdbc.sql(JdbcPlannedFlowRepository.SELECT_FOR_SCENARIO)
                .param("scenarioId", id.value())
                .query(JdbcPlannedFlowRepository::toPlannedFlow)
                .list();
    }

    private record Header(String name, Currency currency, GrowthAssumption growth) {}
}
