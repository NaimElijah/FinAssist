package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What a caller must supply to record a transaction.
 *
 * <p>The entries are {@link Entry} values rather than a transport shape of their own. An entry is
 * exactly an account and a signed amount, so a parallel type would add a mapping loop and a second
 * place to change, and it would delay the domain's own refusal of a zero entry to no purpose.
 *
 * <p>The date is supplied, never read from a clock. That is the rule the whole ledger domain is built
 * on, and it holds one layer further out for the same reason: a use case whose result depends on when
 * it ran cannot be tested without freezing time.
 *
 * <p>{@code recordedBy} names who is asking, not what is being recorded — an {@link Actor}, never an
 * identity the command manufactures (ADR-021, ADR-033).
 *
 * <p>No component may be null. The entries are copied, so a later change to the caller's list does
 * not reach the command, and a null entry is refused as a null list is.
 */
public record RecordTransactionCommand(
        LocalDate occurredOn, String description, List<Entry> entries, Actor recordedBy) {

    public RecordTransactionCommand {
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(recordedBy, "recordedBy");
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }
}
