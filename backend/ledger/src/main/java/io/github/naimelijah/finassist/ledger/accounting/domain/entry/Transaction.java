package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The accounting aggregate root: a dated, described set of entries that <strong>must sum to
 * zero</strong>.
 *
 * <p>That invariant is the whole reason this service is written in Java (ADR-001, ADR-011). It is
 * checked in the constructor, so an unbalanced transaction cannot be built — not saved and rejected
 * later, but never brought into existence. Money cannot be created or destroyed by recording
 * something; it can only move.
 *
 * <p>Buying coffee for 12.50 is one transaction with two entries: {@code +12.50} to an expense
 * account and {@code -12.50} to a current account. A transfer is the same shape across two assets.
 * An opening balance is an asset entry against equity — which is why {@code EQUITY} exists as an
 * account type at all.
 *
 * <p>Every entry is in one currency, because there are no exchange rates here (ADR-012). A
 * cross-currency transaction has no correct representation in this model and is refused.
 */
public record Transaction(
        TransactionId id, LocalDate occurredOn, String description, List<Entry> entries, TransactionState state) {

    /**
     * Creates a transaction, stripping surrounding whitespace from {@code description} and copying
     * {@code entries}.
     *
     * @throws NullPointerException if any component is null
     * @throws IllegalArgumentException if {@code description} is blank, there are fewer than two
     *     entries, or the entries do not sum to zero
     * @throws io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException
     *     if the entries are not all in one currency
     */
    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(entries, "entries");

        if (description.isBlank()) {
            throw new IllegalArgumentException("Transaction description must not be blank");
        }
        description = description.strip();

        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A transaction needs at least two entries; one entry cannot balance");
        }

        Currency currency = entries.getFirst().amount().currency();
        Money total = Money.zero(currency);
        for (Entry entry : entries) {
            // plus() throws CurrencyMismatchException, which is the check for a mixed-currency
            // transaction: there is no rate with which to balance one.
            total = total.plus(entry.amount());
        }
        if (!total.isZero()) {
            throw new IllegalArgumentException(
                    "Entries must sum to zero but sum to " + total + "; the books would not balance");
        }
    }

    /**
     * Records a new transaction under a freshly generated identity. It starts {@code Pending} —
     * nothing has confirmed it yet.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the constructor refuses the entries or the description
     * @throws io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException
     *     if the entries are not all in one currency
     */
    public static Transaction record(LocalDate occurredOn, String description, List<Entry> entries) {
        return new Transaction(
                TransactionId.generate(), occurredOn, description, entries, new TransactionState.Pending());
    }

    /** The single currency every entry is in. */
    public Currency currency() {
        return entries.getFirst().amount().currency();
    }

    /** Whether this transaction counts towards a balance. Voided ones are kept but not counted. */
    public boolean countsTowardsBalance() {
        return !(state instanceof TransactionState.Voided);
    }

    /** The net effect on one account, zero when this transaction does not touch it. */
    public Money netFor(AccountId account) {
        Objects.requireNonNull(account, "account");
        Money net = Money.zero(currency());
        for (Entry entry : entries) {
            if (entry.account().equals(account)) {
                net = net.plus(entry.amount());
            }
        }
        return net;
    }

    /**
     * Confirms the transaction, on a date that cannot precede the day it happened.
     *
     * @return a copy in the {@code Cleared} state; this transaction is unchanged
     * @throws IllegalTransitionException if the transaction is not {@code Pending}
     * @throws NullPointerException if {@code clearedOn} is null
     * @throws IllegalArgumentException if {@code clearedOn} is before {@link #occurredOn}
     */
    public Transaction clear(LocalDate clearedOn) {
        if (!(state instanceof TransactionState.Pending pending)) {
            throw new IllegalTransitionException(state, "clear");
        }
        requireNotBefore(clearedOn, occurredOn, "cleared", "occurred");
        return withState(pending.clear(clearedOn));
    }

    /**
     * Matches the transaction to a statement, on a date that cannot precede the day it cleared.
     *
     * @return a copy in the {@code Reconciled} state; this transaction is unchanged
     * @throws IllegalTransitionException if the transaction is not {@code Cleared}
     * @throws NullPointerException if {@code reconciledOn} is null
     * @throws IllegalArgumentException if {@code reconciledOn} is before the clearing date
     */
    public Transaction reconcile(LocalDate reconciledOn) {
        if (!(state instanceof TransactionState.Cleared cleared)) {
            throw new IllegalTransitionException(state, "reconcile");
        }
        requireNotBefore(reconciledOn, cleared.clearedOn(), "reconciled", "cleared");
        return withState(cleared.reconcile(reconciledOn));
    }

    /**
     * Marks this transaction void. Legal from {@code Pending} and {@code Cleared} only — a
     * reconciled transaction is frozen and is corrected with a reversing transaction instead.
     *
     * <p>A void reverses whatever the transaction had already done, so it cannot predate it. For a
     * cleared transaction that means the clearing date, not merely the date it occurred.
     *
     * @param reason why it is voided; stripped, and never blank
     * @return a copy in the {@code Voided} state; this transaction is unchanged
     * @throws IllegalTransitionException if the transaction is {@code Reconciled} or already
     *     {@code Voided}
     * @throws NullPointerException if {@code voidedOn} or {@code reason} is null
     * @throws IllegalArgumentException if {@code voidedOn} precedes the date the transaction
     *     occurred, or the date it cleared when it had already cleared, or if {@code reason} is
     *     blank
     */
    public Transaction markVoided(LocalDate voidedOn, String reason) {
        requireNotBefore(voidedOn, occurredOn, "voided", "occurred");
        TransactionState next = switch (state) {
            case TransactionState.Pending pending -> pending.markVoided(voidedOn, reason);
            case TransactionState.Cleared cleared -> {
                requireNotBefore(voidedOn, cleared.clearedOn(), "voided", "cleared");
                yield cleared.markVoided(voidedOn, reason);
            }
            case TransactionState.Reconciled ignored -> throw new IllegalTransitionException(state, "void");
            case TransactionState.Voided ignored -> throw new IllegalTransitionException(state, "void");
        };
        return withState(next);
    }

    private Transaction withState(TransactionState next) {
        return new Transaction(id, occurredOn, description, entries, next);
    }

    /**
     * A transaction moves forward in time. Clearing before the purchase happened, or reconciling
     * before it cleared, are states the lifecycle should never have admitted; leaving them
     * representable would make the ordering a convention rather than a rule.
     */
    private static void requireNotBefore(LocalDate later, LocalDate earlier, String laterName, String earlierName) {
        Objects.requireNonNull(later, laterName);
        if (later.isBefore(earlier)) {
            throw new IllegalArgumentException("A transaction cannot be "
                    + laterName
                    + " on "
                    + later
                    + ", before it "
                    + earlierName
                    + " on "
                    + earlier);
        }
    }
}
