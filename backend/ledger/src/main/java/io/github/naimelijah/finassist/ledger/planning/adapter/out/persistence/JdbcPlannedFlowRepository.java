package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Planned flows, in PostgreSQL.
 *
 * <p>A plan is intent, not a fact, so this is ordinary mutable storage and a transition is an
 * update - the asymmetry with the journal is the point ADR-015 makes between the two contexts, and
 * ADR-024 records why the audit obligation stops at accounting. Nothing is ever removed: an
 * abandoned plan becomes {@code Cancelled} carrying its reason, and the port exposes no delete.
 */
public class JdbcPlannedFlowRepository implements PlannedFlowRepository {

    private static final String COLUMNS = """
            id, description, amount, currency, direction, starts_on,
            recurrence, cadence, step_interval, recurs_until, occurrences,
            state, state_on, state_reason
            """;

    private static final String UPSERT = """
            INSERT INTO planning.planned_flow (id, description, amount, currency, direction, starts_on,
                                               recurrence, cadence, step_interval, recurs_until, occurrences,
                                               state, state_on, state_reason)
            VALUES (:id, :description, :amount, :currency, :direction, :startsOn,
                    :recurrence, :cadence, :stepInterval, :recursUntil, :occurrences,
                    :state, :stateOn, :stateReason)
            ON CONFLICT (id) DO UPDATE
               SET description   = EXCLUDED.description,
                   amount        = EXCLUDED.amount,
                   currency      = EXCLUDED.currency,
                   direction     = EXCLUDED.direction,
                   starts_on     = EXCLUDED.starts_on,
                   recurrence    = EXCLUDED.recurrence,
                   cadence       = EXCLUDED.cadence,
                   step_interval = EXCLUDED.step_interval,
                   recurs_until  = EXCLUDED.recurs_until,
                   occurrences   = EXCLUDED.occurrences,
                   state         = EXCLUDED.state,
                   state_on      = EXCLUDED.state_on,
                   state_reason  = EXCLUDED.state_reason
            """;

    private static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM planning.planned_flow WHERE id = :id";

    /** {@code PlannedFlowRepository.all}'s contract: start date, then id, every state included. */
    private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM planning.planned_flow ORDER BY starts_on, id";

    /** Ordered by the position the scenario gave them, which {@code Scenario} treats as its list. */
    static final String SELECT_FOR_SCENARIO = "SELECT flow." + COLUMNS + """
                     FROM planning.scenario_flow link
                     JOIN planning.planned_flow flow ON flow.id = link.flow_id
                    WHERE link.scenario_id = :scenarioId
                    ORDER BY link.ordinal
                    """;

    private final JdbcClient jdbc;

    public JdbcPlannedFlowRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<PlannedFlow> findById(PlanId id) {
        Objects.requireNonNull(id, "id");
        return jdbc.sql(SELECT_BY_ID)
                .param("id", id.value())
                .query(JdbcPlannedFlowRepository::toPlannedFlow)
                .optional();
    }

    @Override
    public List<PlannedFlow> all() {
        return jdbc.sql(SELECT_ALL)
                .query(JdbcPlannedFlowRepository::toPlannedFlow)
                .list();
    }

    @Override
    public void save(PlannedFlow flow) {
        Objects.requireNonNull(flow, "flow");
        PlanningRows.StoredRecurrence recurrence = PlanningRows.StoredRecurrence.of(flow.recurrence());
        PlanningRows.StoredPlanState state = PlanningRows.StoredPlanState.of(flow.state());
        jdbc.sql(UPSERT)
                .param("id", flow.id().value())
                .param("description", flow.description())
                .param("amount", flow.amount().amount())
                .param("currency", flow.amount().currency().name())
                .param("direction", flow.direction().name())
                .param("startsOn", flow.startsOn())
                .param("recurrence", recurrence.kind())
                .param("cadence", recurrence.cadence())
                .param("stepInterval", recurrence.interval())
                .param("recursUntil", recurrence.until())
                .param("occurrences", recurrence.occurrences())
                .param("state", state.state())
                .param("stateOn", state.on())
                .param("stateReason", state.reason())
                .update();
    }

    static PlannedFlow toPlannedFlow(ResultSet row, int rowNumber) throws SQLException {
        PlanningRows.StoredRecurrence recurrence = new PlanningRows.StoredRecurrence(
                row.getString("recurrence"),
                row.getString("cadence"),
                (Integer) row.getObject("step_interval"),
                row.getObject("recurs_until", LocalDate.class),
                (Integer) row.getObject("occurrences"));
        PlanningRows.StoredPlanState state = new PlanningRows.StoredPlanState(
                row.getString("state"), row.getObject("state_on", LocalDate.class), row.getString("state_reason"));
        UUID id = row.getObject("id", UUID.class);

        return new PlannedFlow(
                new PlanId(id),
                row.getString("description"),
                PlanningRows.money(row, PlanningRows.PLANNED_FLOW, "id=" + id, "amount", "currency"),
                PlanningRows.enumerated(
                        Direction.class, PlanningRows.PLANNED_FLOW, "direction", row.getString("direction")),
                row.getObject("starts_on", LocalDate.class),
                recurrence.toDomain(),
                state.toDomain());
    }
}
