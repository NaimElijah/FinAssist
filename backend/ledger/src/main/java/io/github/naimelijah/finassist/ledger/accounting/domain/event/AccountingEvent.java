package io.github.naimelijah.finassist.ledger.accounting.domain.event;

import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Something that happened in the accounting context, stated in the past tense.
 *
 * <p>Sealed, so a consumer can switch over every event the context can raise and the compiler will
 * point at the switch when a new one is added. Events carry identifiers and dates rather than whole
 * aggregates: an event that carries a {@code Transaction} would drag the aggregate across whatever
 * boundary the event crosses.
 *
 * <p>Nothing publishes these yet — publication belongs to the application layer, which is out of
 * scope here. They exist now because the vocabulary is part of the domain, not of the plumbing.
 */
public sealed interface AccountingEvent {

    TransactionId transaction();

    LocalDate occurredOn();

    record TransactionRecorded(TransactionId transaction, LocalDate occurredOn) implements AccountingEvent {
        public TransactionRecorded {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(occurredOn, "occurredOn");
        }
    }

    record TransactionCleared(TransactionId transaction, LocalDate occurredOn) implements AccountingEvent {
        public TransactionCleared {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(occurredOn, "occurredOn");
        }
    }

    record TransactionReconciled(TransactionId transaction, LocalDate occurredOn) implements AccountingEvent {
        public TransactionReconciled {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(occurredOn, "occurredOn");
        }
    }

    record TransactionVoided(TransactionId transaction, LocalDate occurredOn, String reason)
            implements AccountingEvent {
        public TransactionVoided {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(occurredOn, "occurredOn");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
