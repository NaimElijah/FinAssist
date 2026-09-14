package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
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
     * hides; the same choice {@code save} makes by never deleting one.
     */
    List<PlannedFlow> all();

    /** Stores the flow, replacing any earlier state of the same identity. */
    void save(PlannedFlow flow);
}
