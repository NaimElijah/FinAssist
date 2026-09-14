package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import java.time.LocalDate;

/**
 * Where a transaction has got to: a status, the date it got there, and for a void the reason (ADR-028).
 *
 * <p>The domain's sealed {@link TransactionState} is mapped here by hand, one variant at a time, rather
 * than by a Jackson polymorphism annotation, which the domain may not carry. The switch is exhaustive
 * over the sealed interface, so a new state does not compile until it has a wire shape. A member a
 * variant lacks is omitted rather than written as null: a pending transaction has no {@code on}, and
 * only a voided one has a {@code reason}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionStateResponse(Status status, LocalDate on, String reason) {

    /** The discriminator a client branches on, one constant per state. */
    public enum Status {
        PENDING,
        CLEARED,
        RECONCILED,
        VOIDED
    }

    static TransactionStateResponse from(TransactionState state) {
        return switch (state) {
            case TransactionState.Pending ignored -> new TransactionStateResponse(Status.PENDING, null, null);
            case TransactionState.Cleared cleared ->
                new TransactionStateResponse(Status.CLEARED, cleared.clearedOn(), null);
            case TransactionState.Reconciled reconciled ->
                new TransactionStateResponse(Status.RECONCILED, reconciled.reconciledOn(), null);
            case TransactionState.Voided voided ->
                new TransactionStateResponse(Status.VOIDED, voided.voidedOn(), voided.reason());
        };
    }
}
