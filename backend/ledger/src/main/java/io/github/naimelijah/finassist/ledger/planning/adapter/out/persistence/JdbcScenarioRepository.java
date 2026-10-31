package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    private static final String SCENARIO_TABLE = "planning.scenario";
    private static final String SCENARIO_FLOW_TABLE = "planning.scenario_flow";

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
            INSERT INTO planning.scenario_flow (scenario_id, ordinal, flow_id, currency)
            VALUES (:scenarioId, :ordinal, :flowId, :currency)
            """;

    private static final String SELECT_SCENARIO = """
            SELECT id, name, currency, growth_per_step FROM planning.scenario WHERE id = :id
            """;

    /** {@code ScenarioRepository.all}'s contract: name, then id, since names are not unique. */
    private static final String SELECT_ALL_SCENARIOS = """
            SELECT id, name, currency, growth_per_step
              FROM planning.scenario
             ORDER BY name, id
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
                .query(this::toScenario)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads each scenario's flows with {@link #flowsOf}, the same helper {@link #findById} uses —
     * one further query per row rather than a join. One owner keeps few scenarios, and ADR-028
     * accepts that cost the same way it does for {@code JdbcBudgetRepository.all()}.
     */
    @Override
    public List<Scenario> all() {
        return jdbc.sql(SELECT_ALL_SCENARIOS).query(this::toScenario).list();
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
            PlannedFlow flow = flows.get(ordinal);
            jdbc.sql(INSERT_FLOW)
                    .param("scenarioId", scenario.id().value())
                    .param("ordinal", ordinal)
                    .param("flowId", flow.id().value())
                    // The flow's own currency, so the key to the scenario is what compares the two.
                    .param("currency", flow.currency().name())
                    .update();
        }
    }

    private List<PlannedFlow> flowsOf(ScenarioId id) {
        return jdbc.sql(JdbcPlannedFlowRepository.SELECT_FOR_SCENARIO)
                .param("scenarioId", id.value())
                .query(JdbcPlannedFlowRepository::toPlannedFlow)
                .list();
    }

    /**
     * Shared by {@link #findById} and {@link #all}, so the two never map a row two different ways.
     *
     * <p>Each column reads back on its own, and {@code scenario_flow_in_scenario_currency} and {@code
     * scenario_flow_in_plan_currency} tie every flow's currency to the scenario's (V3_2). {@link Scenario}
     * still checks every rule on the way back in, some more strictly than the schema can, such as a name
     * that only Java counts as blank; a stored scenario it refuses is a server fault naming the scenario
     * rather than a rule a caller broke.
     */
    private Scenario toScenario(ResultSet row, int rowNumber) throws SQLException {
        ScenarioId id = new ScenarioId(row.getObject("id", UUID.class));
        String name = row.getString("name");
        Currency currency = PlanningRows.currency(row, SCENARIO_TABLE, "currency");
        List<PlannedFlow> flows = flowsOf(id);
        try {
            // The constructor re-applies stripTrailingZeros, so a rate stored as 100 and read back at
            // scale 0 normalises to the same value the caller wrote.
            GrowthAssumption growth = new GrowthAssumption(row.getBigDecimal("growth_per_step"));
            return new Scenario(id, name, currency, flows, growth);
        } catch (IllegalArgumentException refused) {
            throw PlanningRows.unreconstructable(
                    "scenario", id.value(), SCENARIO_TABLE + " and " + SCENARIO_FLOW_TABLE);
        }
    }
}
