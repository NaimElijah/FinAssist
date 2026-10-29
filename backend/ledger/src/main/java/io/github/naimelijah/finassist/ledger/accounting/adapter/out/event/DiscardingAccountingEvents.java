package io.github.naimelijah.finassist.ledger.accounting.adapter.out.event;

import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import java.util.Objects;

/**
 * The production publisher: it accepts every event and keeps none.
 *
 * <p>Nothing consumes an accounting event yet, so a retained log would be memory that grows with every
 * write and that nothing reads (ADR-048). The services still raise each event, and the use-case tests
 * still read what was raised from a recording double, so the port stays exercised. The transactional
 * outbox arrives with the first consumer, and replaces this class (ADR-023, ADR-048).
 *
 * <p>An event is not the audit record — the journal is (ADR-020) — so discarding one loses nothing the
 * ledger must keep.
 */
public class DiscardingAccountingEvents implements AccountingEvents {

    /**
     * Discards the event.
     *
     * @throws NullPointerException if {@code event} is null, as the recording double does, so a service
     *     that publishes a null fails in production exactly as it fails its tests
     */
    @Override
    public void publish(AccountingEvent event) {
        Objects.requireNonNull(event, "event");
    }
}
