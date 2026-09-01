package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;

/** Where planning announces what it did. The mirror of {@code AccountingEvents}; see ADR-023. */
public interface PlanningEvents {

    void publish(PlanningEvent event);
}
