package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A transaction as the journal holds it: its id, when it occurred, what it was, its entries and its
 * state. Every member is always present, and marked required in the contract.
 */
public record TransactionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The date the transaction occurred, which a listing's from and to select on.")
        LocalDate occurredOn,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<EntryDto> entries,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransactionStateResponse state) {

    static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.id().value(),
                transaction.occurredOn(),
                transaction.description(),
                transaction.entries().stream().map(EntryDto::from).toList(),
                TransactionStateResponse.from(transaction.state()));
    }
}
