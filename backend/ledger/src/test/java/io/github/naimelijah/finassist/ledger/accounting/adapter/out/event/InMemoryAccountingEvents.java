package io.github.naimelijah.finassist.ledger.accounting.adapter.out.event;

import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double that keeps every event it is given, in the order it was given them, and reads them
 * back, so a use-case test can assert what a transition raised.
 *
 * <p>Test code only. The running ledger wires {@code DiscardingAccountingEvents}, which keeps nothing,
 * because a log nothing reads grows with every write (ADR-048). This one grows without bound too,
 * which is harmless in a test that lives for one case.
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
