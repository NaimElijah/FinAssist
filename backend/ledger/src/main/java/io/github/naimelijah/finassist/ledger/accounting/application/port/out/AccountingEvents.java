package io.github.naimelijah.finassist.ledger.accounting.application.port.out;

import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;

/**
 * Where accounting announces what it did.
 *
 * <p>A port rather than a framework publisher, for two reasons. The application layer imports no
 * framework — {@code LedgerArchitectureTest.the_application_layer_depends_on_no_framework} fails the
 * build for {@code org.springframework..} — and a use-case test asserts what was raised by reading a
 * fake, which is what keeps these events exercised rather than decorative.
 *
 * <p>Nothing subscribes yet. ADR-023 records that as the accepted cost and names the trigger that
 * discharges it.
 */
public interface AccountingEvents {

    void publish(AccountingEvent event);
}
