package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * What a client supplies to record a transaction: the date it occurred, a description and at least two
 * entries. It has no id, because identity is generated inside the service (ADR-021).
 */
public record RecordTransactionRequest(
        @NotNull LocalDate occurredOn,
        @NotBlank String description,

        @NotNull @Size(min = RecordTransactionRequest.MINIMUM_ENTRIES)
        List<@NotNull @Valid EntryDto> entries) {

    /**
     * One entry cannot balance. {@code Transaction} refuses a single entry too; checking it here as well
     * makes a short list a 400 naming the field, like any other malformed body.
     */
    static final int MINIMUM_ENTRIES = 2;

    /**
     * @throws IllegalArgumentException if an entry is zero, or carries more precision than its currency
     *     admits
     */
    RecordTransactionCommand toCommand(Actor recordedBy) {
        return new RecordTransactionCommand(
                occurredOn, description, entries.stream().map(EntryDto::toEntry).toList(), recordedBy);
    }
}
