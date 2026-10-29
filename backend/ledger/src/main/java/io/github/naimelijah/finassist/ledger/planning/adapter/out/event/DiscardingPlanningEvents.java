package io.github.naimelijah.finassist.ledger.planning.adapter.out.event;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;
import java.util.Objects;

/**
 * The production publisher: it accepts every event and keeps none. The mirror of {@code
 * DiscardingAccountingEvents}, for the same reason — nothing consumes a planning event yet, and the
 * outbox arrives with the first consumer (ADR-023, ADR-048).
 */
public class DiscardingPlanningEvents implements PlanningEvents {

    /**
     * Discards the event.
     *
     * @throws NullPointerException if {@code event} is null, as the recording double does
     */
    @Override
    public void publish(PlanningEvent event) {
        Objects.requireNonNull(event, "event");
    }
}
