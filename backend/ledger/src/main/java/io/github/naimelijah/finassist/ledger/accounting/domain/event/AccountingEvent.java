package io.github.naimelijah.finassist.ledger.accounting.domain.event;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Something that happened in the books, stated in the past tense.
 *
 * <p>These are raised by the application service that performed the change, not by the aggregate.
 * {@code Transaction} is a record and its value semantics are load-bearing — the suites compare whole
 * aggregates, and {@code Scenario} refuses a duplicate flow by comparing values — so hanging a mutable
 * pending-events list off one would break its equality and its immutability at once. The types belong
 * to the domain because they speak its vocabulary; the raising belongs to the service because that is
 * what knows a transition succeeded. See {@code docs/ADRs/ADR-023-domain-events-in-the-ledger.md}.
 *
 * <p>Sealed, so the first subscriber gets an exhaustive {@code switch} and the compiler tells it about
 * a new event rather than a default branch quietly dropping one.
 *
 * <p><strong>An event is not the audit record.</strong> ADR-020 makes the append-only journal that, and
 * the running ledger keeps no event at all (ADR-048). Nothing here may be cited as evidence of a
 * financial change.
 */
public sealed interface AccountingEvent {

    /** An account joined the chart of accounts. */
    record AccountOpened(AccountId account, String name, AccountType type, Currency currency)
            implements AccountingEvent {

        public AccountOpened {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(currency, "currency");
        }
    }

    /**
     * A balanced transaction entered the journal.
     *
     * @param accounts every account the transaction touches, so a subscriber can decide whether it cares
     *     without loading the transaction back
     */
    record TransactionRecorded(
            TransactionId transaction, LocalDate occurredOn, String description, List<AccountId> accounts)
            implements AccountingEvent {

        public TransactionRecorded {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(occurredOn, "occurredOn");
            Objects.requireNonNull(description, "description");
            accounts = List.copyOf(accounts);
        }
    }

    /** The institution confirmed the transaction. */
    record TransactionCleared(TransactionId transaction, LocalDate clearedOn) implements AccountingEvent {

        public TransactionCleared {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(clearedOn, "clearedOn");
        }
    }

    /** The transaction was matched against a statement, and is now frozen. */
    record TransactionReconciled(TransactionId transaction, LocalDate reconciledOn) implements AccountingEvent {

        public TransactionReconciled {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(reconciledOn, "reconciledOn");
        }
    }

    /** The transaction was voided. Its entries remain; they stop counting towards a balance. */
    record TransactionVoided(TransactionId transaction, LocalDate voidedOn, String reason) implements AccountingEvent {

        public TransactionVoided {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(voidedOn, "voidedOn");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
