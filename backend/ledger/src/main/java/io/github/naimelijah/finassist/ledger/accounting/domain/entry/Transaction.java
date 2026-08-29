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

    /** Records a new transaction. It starts {@code Pending} — nothing has confirmed it yet. */
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

    public Transaction clear(LocalDate clearedOn) {
        if (!(state instanceof TransactionState.Pending pending)) {
            throw new IllegalTransitionException(state, "clear");
        }
        return withState(pending.clear(clearedOn));
    }

    public Transaction reconcile(LocalDate reconciledOn) {
        if (!(state instanceof TransactionState.Cleared cleared)) {
            throw new IllegalTransitionException(state, "reconcile");
        }
        return withState(cleared.reconcile(reconciledOn));
    }

    /**
     * Marks this transaction void. Legal from {@code Pending} and {@code Cleared} only — a
     * reconciled transaction is frozen and is corrected with a reversing transaction instead.
     */
    public Transaction markVoided(LocalDate voidedOn, String reason) {
        TransactionState next =
                switch (state) {
                    case TransactionState.Pending pending -> pending.markVoided(voidedOn, reason);
                    case TransactionState.Cleared cleared -> cleared.markVoided(voidedOn, reason);
                    case TransactionState.Reconciled ignored -> throw new IllegalTransitionException(state, "void");
                    case TransactionState.Voided ignored -> throw new IllegalTransitionException(state, "void");
                };
        return withState(next);
    }

    private Transaction withState(TransactionState next) {
        return new Transaction(id, occurredOn, description, entries, next);
    }
}
