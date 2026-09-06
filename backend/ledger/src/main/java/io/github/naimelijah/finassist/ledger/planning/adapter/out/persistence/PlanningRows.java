package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import java.time.LocalDate;

/**
 * The two sealed types planning stores, encoded as a discriminator and the columns each variant
 * needs, and decoded back.
 *
 * <p>Kept in one place because the encoding and the decoding have to agree, and a mismatch between
 * them is the kind of defect that only shows up on a read months later. The {@code CHECK}
 * constraints in {@code V3_1__planning_schema.sql} say the same thing in SQL, so a combination
 * neither Java nor this class can produce is one the database will not hold either.
 */
final class PlanningRows {

    private PlanningRows() {}

    /** The stored shape of a {@link Recurrence}: which variant, and its parameters. */
    record StoredRecurrence(String kind, String cadence, Integer interval, LocalDate until, Integer occurrences) {

        static StoredRecurrence of(Recurrence recurrence) {
            return switch (recurrence) {
                case Recurrence.Once ignored -> new StoredRecurrence("ONCE", null, null, null, null);
                case Recurrence.Until until ->
                    new StoredRecurrence("UNTIL", until.cadence().name(), until.interval(), until.until(), null);
                case Recurrence.Times times ->
                    new StoredRecurrence("TIMES", times.cadence().name(), times.interval(), null, times.count());
            };
        }

        Recurrence toDomain() {
            return switch (kind) {
                case "ONCE" -> new Recurrence.Once();
                case "UNTIL" -> new Recurrence.Until(Cadence.valueOf(cadence), interval, until);
                case "TIMES" -> new Recurrence.Times(Cadence.valueOf(cadence), interval, occurrences);
                default -> throw new IllegalStateException("Unknown stored recurrence: " + kind);
            };
        }
    }

    /** The stored shape of a {@link PlanState}: which variant, when, and why where there is a why. */
    record StoredPlanState(String state, LocalDate on, String reason) {

        static StoredPlanState of(PlanState state) {
            return switch (state) {
                case PlanState.Planned ignored -> new StoredPlanState("PLANNED", null, null);
                case PlanState.Committed committed -> new StoredPlanState("COMMITTED", committed.committedOn(), null);
                case PlanState.Realised realised -> new StoredPlanState("REALISED", realised.realisedOn(), null);
                case PlanState.Cancelled cancelled ->
                    new StoredPlanState("CANCELLED", cancelled.cancelledOn(), cancelled.reason());
            };
        }

        PlanState toDomain() {
            return switch (state) {
                case "PLANNED" -> new PlanState.Planned();
                case "COMMITTED" -> new PlanState.Committed(on);
                case "REALISED" -> new PlanState.Realised(on);
                case "CANCELLED" -> new PlanState.Cancelled(on, reason);
                default -> throw new IllegalStateException("Unknown stored plan state: " + state);
            };
        }
    }
}
