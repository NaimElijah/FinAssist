package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A transaction as the journal holds it: its id, when it occurred, what it was, its entries and its state. */
public record TransactionResponse(
        UUID id, LocalDate occurredOn, String description, List<EntryDto> entries, TransactionStateResponse state) {

    static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.id().value(),
                transaction.occurredOn(),
                transaction.description(),
                transaction.entries().stream().map(EntryDto::from).toList(),
                TransactionStateResponse.from(transaction.state()));
    }
}
