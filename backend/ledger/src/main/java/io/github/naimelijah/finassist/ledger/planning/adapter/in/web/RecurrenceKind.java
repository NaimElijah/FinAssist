package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

/**
 * The discriminator of a recurrence on the wire, one constant per variant of the domain's sealed {@code
 * Recurrence} (ADR-028). Shared by {@link RecurrenceRequest} and {@link RecurrenceResponse}, so the two
 * directions cannot spell a kind differently.
 */
public enum RecurrenceKind {
    /** Happens once, on the flow's start date. */
    ONCE,
    /** Repeats on a cadence up to, but not including, a date. */
    UNTIL,
    /** Repeats on a cadence a fixed number of times. */
    TIMES
}
