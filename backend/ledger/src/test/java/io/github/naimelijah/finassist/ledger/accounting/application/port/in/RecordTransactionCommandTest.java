package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** {@code RecordTransactionCommand}'s own validation, kept apart from the use case that takes it. */
class RecordTransactionCommandTest {

    @Test
    void Given_no_entries_When_a_command_is_built_Then_it_is_refused_naming_the_field() {
        assertThatThrownBy(
                        () -> new RecordTransactionCommand(LocalDate.of(2026, 3, 1), "Coffee", null, new Actor("sub")))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("entries");
    }
}
