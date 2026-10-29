package io.github.naimelijah.finassist.ledger.accounting.adapter.out.event;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import org.junit.jupiter.api.Test;

/**
 * The production publisher. What it keeps cannot be observed, because it keeps nothing; what can is
 * that it never fails a write it is handed an event for, and that it refuses a null exactly as the
 * recording double the service tests use does, so swapping one for the other hides no bug.
 */
class DiscardingAccountingEventsTest {

    private final DiscardingAccountingEvents events = new DiscardingAccountingEvents();

    @Test
    void Given_an_event_When_published_Then_it_is_accepted() {
        AccountingEvent opened =
                new AccountingEvent.AccountOpened(AccountId.generate(), "Groceries", AccountType.EXPENSE, Currency.ILS);

        assertThatNoException().isThrownBy(() -> events.publish(opened));
    }

    @Test
    void Given_no_event_When_published_Then_it_is_refused() {
        assertThatThrownBy(() -> events.publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("event");
    }
}
