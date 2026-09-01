package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
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

    /** Stores the flow, replacing any earlier state of the same identity. */
    void save(PlannedFlow flow);
}
