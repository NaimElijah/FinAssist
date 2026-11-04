package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.ConcurrentPlanTransitionException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.shared.Money;
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
 *
 * <p>That update is conditional on the state the move was validated against, so two moves decided on
 * one read cannot both land (ADR-075). One statement is enough for that, with neither the transaction
 * nor the isolation level the journal's adapter needs; {@link #saveTransition} says why. {@link #save}
 * only inserts, so the conditional update is the one way to change a stored flow.
 */
public class JdbcPlannedFlowRepository implements PlannedFlowRepository {

    private static final String COLUMNS = """
            id, description, amount, currency, direction, starts_on,
            recurrence, cadence, step_interval, recurs_until, occurrences,
            state, state_on, state_reason
            """;

    private static final String INSERT = """
            INSERT INTO planning.planned_flow (id, description, amount, currency, direction, starts_on,
                                               recurrence, cadence, step_interval, recurs_until, occurrences,
                                               state, state_on, state_reason)
            VALUES (:id, :description, :amount, :currency, :direction, :startsOn,
                    :recurrence, :cadence, :stepInterval, :recursUntil, :occurrences,
                    :state, :stateOn, :stateReason)
            """;

    /**
     * Compares the discriminator alone: the lifecycle has no cycle, and a stored state's date and reason
     * change only along with it, so an equal discriminator means nothing has moved (ADR-075).
     */
    private static final String UPDATE_STATE = """
            UPDATE planning.planned_flow
               SET state = :state, state_on = :stateOn, state_reason = :stateReason
             WHERE id = :id AND state = :validatedAgainst
            """;

    private static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM planning.planned_flow WHERE id = :id";

    /** {@code PlannedFlowRepository.all}'s contract: start date, then id, every state included. */
    private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM planning.planned_flow ORDER BY starts_on, id";

    /**
     * Ordered by the position the scenario gave them, which {@code Scenario} treats as its list. The
     * link is narrowed to the two columns the join needs, because it carries a {@code currency} of its
     * own that would make the unqualified {@code COLUMNS} ambiguous.
     */
    static final String SELECT_FOR_SCENARIO = "SELECT " + COLUMNS + """
                     FROM planning.planned_flow flow
                     JOIN (SELECT flow_id, ordinal
                             FROM planning.scenario_flow
                            WHERE scenario_id = :scenarioId) link
                       ON link.flow_id = flow.id
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
        jdbc.sql(INSERT)
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

    /**
     * {@inheritDoc}
     *
     * <p>Nothing above {@code READ COMMITTED}, unlike the journal's {@code saveTransition} (ADR-075). There
     * is no read to keep on one snapshot with a trigger: the comparison is this statement's own {@code
     * WHERE}, so no rival can land between the check and the write. An {@code UPDATE} that waits on a
     * rival's uncommitted row re-checks that {@code WHERE} against the row the rival committed, and
     * updates nothing if the state moved. That is {@code READ COMMITTED}; at {@code REPEATABLE READ} the
     * same wait would raise a serialization failure instead, which nothing here maps. So it needs no
     * {@code @Transactional} today, and a transaction around it is fine as long as it stays at that level.
     */
    @Override
    public void saveTransition(PlanState validatedAgainst, PlannedFlow next) {
        Objects.requireNonNull(validatedAgainst, "validatedAgainst");
        Objects.requireNonNull(next, "next");
        PlanningRows.StoredPlanState state = PlanningRows.StoredPlanState.of(next.state());
        int updated = jdbc.sql(UPDATE_STATE)
                .param("id", next.id().value())
                .param("state", state.state())
                .param("stateOn", state.on())
                .param("stateReason", state.reason())
                .param(
                        "validatedAgainst",
                        PlanningRows.StoredPlanState.of(validatedAgainst).state())
                .update();
        if (updated == 0) {
            throw new ConcurrentPlanTransitionException(next.id());
        }
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
        String description = row.getString("description");
        Money amount = PlanningRows.money(row, PlanningRows.PLANNED_FLOW, "id=" + id, "amount", "currency");
        Direction direction = PlanningRows.enumerated(
                Direction.class, PlanningRows.PLANNED_FLOW, "direction", row.getString("direction"));
        LocalDate startsOn = row.getObject("starts_on", LocalDate.class);

        // PlannedFlow and its recurrence and state still check every rule on the way back in, some
        // more strictly than the schema can, such as a description only Java counts as blank (#402).
        try {
            return new PlannedFlow(
                    new PlanId(id), description, amount, direction, startsOn, recurrence.toDomain(), state.toDomain());
        } catch (IllegalArgumentException refused) {
            throw PlanningRows.unreconstructable("planned flow", id, PlanningRows.PLANNED_FLOW);
        }
    }
}
