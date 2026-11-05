package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import java.util.List;
import java.util.Optional;

/**
 * Where intentions are kept.
 *
 * <p>No delete, like every repository port here. A plan that is abandoned becomes {@code Cancelled}
 * carrying its reason and stops counting towards a projection; it does not stop existing. That is the
 * same choice voiding makes in accounting, for the same reason.
 */
public interface PlannedFlowRepository {

    Optional<PlannedFlow> findById(PlanId id);

    /**
     * Every planned flow, ordered by start date then id — {@code ORDER BY starts_on, id} — with
     * every state included. A cancelled or realised flow is history, not something this listing
     * hides; the same choice this port makes by exposing no delete.
     */
    List<PlannedFlow> all();

    /**
     * Records a new flow. A lifecycle change goes through {@link #saveTransition}, so a stored identity
     * cannot be overwritten here.
     *
     * <p>Precondition: the identity is new. The durable store refuses one already stored, as a fault in
     * the caller that nothing catches and not as one of the refusals a client is answered.
     */
    void save(PlannedFlow flow);

    /**
     * Stores one lifecycle move: writes the state of {@code next}, provided the stored state is still the
     * one the move was decided on.
     *
     * <p>The caller passes the state it validated against, the one it read before asking {@link
     * PlannedFlow} for the move, not whatever it believes is current. That is the whole check: a move is
     * decided on one read and written later, and without it two moves decided on the same read could both
     * land, the later one leaving no trace of the earlier (ADR-075).
     *
     * <p>Only the state is written, whatever else {@code next} carries: a flow's other fields never change
     * through a transition, and the domain cannot produce a {@code next} that differs.
     *
     * <p>Preconditions: {@code next} is one move {@link PlannedFlow} allows from {@code validatedAgainst},
     * since no trigger stands behind this as one does behind the journal, so the store admits whatever it
     * is given; and the flow is stored. One that never was is refused exactly as a stale move is, and the
     * two are not told apart, because the port has no delete and the only caller has just read the flow.
     *
     * @param validatedAgainst the state the move was decided on
     * @param next the flow in its new state
     * @throws io.github.naimelijah.finassist.ledger.planning.application.port.in.ConcurrentPlanTransitionException
     *     if the stored state is no longer {@code validatedAgainst}; nothing was stored
     */
    void saveTransition(PlanState validatedAgainst, PlannedFlow next);
}
