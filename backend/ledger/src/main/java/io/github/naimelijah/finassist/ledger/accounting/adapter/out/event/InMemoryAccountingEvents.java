package io.github.naimelijah.finassist.ledger.accounting.adapter.out.event;

import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps every event it is given, in the order it was given them, and reads them back.
 *
 * <p>Deliberately the shape an outbox has. When persistence lands, ADR-020's append-only obligation
 * becomes a question about which rows are written, not about what this port looks like — which is the
 * whole reason for shipping an unconsumed event log rather than none at all (ADR-023).
 *
 * <p>It grows without bound, which is correct for a development seam and disqualifying for a store.
 * That is one more reason it is not one.
 */
public class InMemoryAccountingEvents implements AccountingEvents {

    private final List<AccountingEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AccountingEvent event) {
        Objects.requireNonNull(event, "event");
        published.add(event);
    }

    /** Everything published so far, oldest first. Never truncated. */
    public List<AccountingEvent> published() {
        return List.copyOf(published);
    }
}
