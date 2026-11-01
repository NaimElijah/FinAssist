package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;

/** Where planning announces what it did. The mirror of {@code AccountingEvents}; see ADR-023. */
public interface PlanningEvents {

    /**
     * Announces an event. The application services call it after the change it describes is
     * saved.
     */
    void publish(PlanningEvent event);
}
