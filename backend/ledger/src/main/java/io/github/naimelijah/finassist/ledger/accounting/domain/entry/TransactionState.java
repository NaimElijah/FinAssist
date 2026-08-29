package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Where a transaction has got to, and what may still happen to it.
 *
 * <p>Legal transitions: {@code Pending -> Cleared -> Reconciled}, and {@code Pending} or
 * {@code Cleared -> Voided}. {@link Reconciled} is <strong>frozen</strong>: it has been matched
 * against a statement, so changing it would silently disagree with a document the bank also holds.
 * Correct a reconciled transaction with a reversing transaction instead.
 *
 * <p>That freeze is expressed by <em>absence</em>. {@code Reconciled} declares no transition method
 * at all, so {@code reconciled.clear()} does not compile. {@link Transaction} holds this type as the
 * interface rather than a concrete state, so it checks at run time and throws
 * {@link IllegalTransitionException}; both guards exist because both call sites exist.
 *
 * <p>Every transition takes the date rather than reading a clock, so the domain stays pure and its
 * tests stay deterministic.
 */
public sealed interface TransactionState {

    /** A short name for messages and events. */
    String name();

    /** Recorded, but not yet confirmed by the institution. */
    record Pending() implements TransactionState {

        public Cleared clear(LocalDate clearedOn) {
            return new Cleared(clearedOn);
        }

        public Voided markVoided(LocalDate voidedOn, String reason) {
            return new Voided(voidedOn, reason);
        }

        @Override
        public String name() {
            return "Pending";
        }
    }

    /** Confirmed by the institution, not yet matched against a statement. */
    record Cleared(LocalDate clearedOn) implements TransactionState {

        public Cleared {
            Objects.requireNonNull(clearedOn, "clearedOn");
        }

        public Reconciled reconcile(LocalDate reconciledOn) {
            return new Reconciled(reconciledOn);
        }

        public Voided markVoided(LocalDate voidedOn, String reason) {
            return new Voided(voidedOn, reason);
        }

        @Override
        public String name() {
            return "Cleared";
        }
    }

    /** Matched against a statement. Terminal by design: no transition method exists. */
    record Reconciled(LocalDate reconciledOn) implements TransactionState {

        public Reconciled {
            Objects.requireNonNull(reconciledOn, "reconciledOn");
        }

        @Override
        public String name() {
            return "Reconciled";
        }
    }

    /** Reversed. Its entries are kept as history but excluded from every balance. */
    record Voided(LocalDate voidedOn, String reason) implements TransactionState {

        public Voided {
            Objects.requireNonNull(voidedOn, "voidedOn");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("Voiding a transaction requires a reason");
            }
            reason = reason.strip();
        }

        @Override
        public String name() {
            return "Voided";
        }
    }
}
