package io.github.naimelijah.finassist.ledger.accounting.domain.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The events' own validation, kept apart from the services that raise them. */
class AccountingEventTest {

    @Test
    void Given_no_accounts_When_a_transaction_recorded_event_is_built_Then_it_is_refused_naming_the_field() {
        assertThatThrownBy(() -> new AccountingEvent.TransactionRecorded(
                        TransactionId.generate(), LocalDate.of(2026, 3, 1), "Coffee", null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("accounts");
    }
}
