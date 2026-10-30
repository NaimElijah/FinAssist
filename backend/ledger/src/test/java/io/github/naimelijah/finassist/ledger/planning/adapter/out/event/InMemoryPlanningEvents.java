package io.github.naimelijah.finassist.ledger.planning.adapter.out.event;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double that keeps every event it is given, in order, and reads them back. The mirror of
 * {@code InMemoryAccountingEvents}; the running ledger wires {@code DiscardingPlanningEvents} instead
 * (ADR-048).
 */
public class InMemoryPlanningEvents implements PlanningEvents {

    private final List<PlanningEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(PlanningEvent event) {
        Objects.requireNonNull(event, "event");
        published.add(event);
    }

    /** Everything published so far, oldest first. Never truncated. */
    public List<PlanningEvent> published() {
        return List.copyOf(published);
    }
}
