package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A development seam, not the store. ADR-006 is still Proposed; this adds no driver and no schema, and
 * exists so the application layer is runnable while the store is an open decision.
 */
public class InMemoryPlannedFlowRepository implements PlannedFlowRepository {

    private final Map<PlanId, PlannedFlow> flows = new ConcurrentHashMap<>();

    @Override
    public Optional<PlannedFlow> findById(PlanId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(flows.get(id));
    }

    @Override
    public void save(PlannedFlow flow) {
        Objects.requireNonNull(flow, "flow");
        flows.put(flow.id(), flow);
    }
}
