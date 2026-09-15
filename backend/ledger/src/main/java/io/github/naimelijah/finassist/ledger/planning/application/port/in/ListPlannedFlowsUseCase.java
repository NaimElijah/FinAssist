package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import java.util.List;

/** Reads every recorded intention. */
public interface ListPlannedFlowsUseCase {

    /**
     * Every planned flow, ordered by start date then id, with every state included — cancelled and
     * realised flows are history, not something a listing hides.
     */
    List<PlannedFlow> all();
}
