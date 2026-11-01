package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Where a planned flow has got to, and what may still happen to it.
 *
 * <p>Legal transitions: {@code Planned -> Committed -> Realised}, and {@code Planned} or
 * {@code Committed -> Cancelled}. {@link Realised} is <strong>frozen</strong>: the money has
 * actually moved, so the fact now lives in the ledger as a {@code Transaction} and this record is
 * only the intention that preceded it. Editing it afterwards would put a plan and a fact into
 * disagreement with nothing to arbitrate between them.
 *
 * <p>That freeze is expressed by <em>absence</em>. {@code Realised} declares no transition method at
 * all, so {@code realised.cancel(...)} does not compile. {@link PlannedFlow} holds this type as the
 * interface rather than a concrete state, so it also checks at run time and throws
 * {@link IllegalPlanTransitionException}; both guards exist because both call sites exist. This is
 * the same shape as {@code accounting.TransactionState}, deliberately — two lifecycles that behave
 * differently for no reason are two things to remember.
 *
 * <p>Every transition takes the date rather than reading a clock, so the domain stays pure and its
 * tests stay deterministic.
 */
public sealed interface PlanState {

    /** A short name for messages. */
    String name();

    /** Intended, but not yet decided on. */
    record Planned() implements PlanState {

        /**
         * Decides on the plan.
         *
         * @throws NullPointerException if {@code committedOn} is null
         */
        public Committed commit(LocalDate committedOn) {
            return new Committed(committedOn);
        }

        /**
         * Abandons the plan.
         *
         * @throws NullPointerException if {@code cancelledOn} or {@code reason} is null
         * @throws IllegalArgumentException if {@code reason} is blank
         */
        public Cancelled cancel(LocalDate cancelledOn, String reason) {
            return new Cancelled(cancelledOn, reason);
        }

        @Override
        public String name() {
            return "Planned";
        }
    }

    /** Decided on and expected to happen, but the money has not moved yet. */
    record Committed(LocalDate committedOn) implements PlanState {

        public Committed {
            Objects.requireNonNull(committedOn, "committedOn");
        }

        /**
         * Records that the money moved. Does not compare {@code realisedOn} with
         * {@link #committedOn()}; {@link PlannedFlow#realise} does.
         *
         * @throws NullPointerException if {@code realisedOn} is null
         */
        public Realised realise(LocalDate realisedOn) {
            return new Realised(realisedOn);
        }

        /**
         * Abandons the plan. Does not compare {@code cancelledOn} with {@link #committedOn()};
         * {@link PlannedFlow#cancel} does.
         *
         * @throws NullPointerException if {@code cancelledOn} or {@code reason} is null
         * @throws IllegalArgumentException if {@code reason} is blank
         */
        public Cancelled cancel(LocalDate cancelledOn, String reason) {
            return new Cancelled(cancelledOn, reason);
        }

        @Override
        public String name() {
            return "Committed";
        }
    }

    /** The money moved. Terminal by design: no transition method exists. */
    record Realised(LocalDate realisedOn) implements PlanState {

        public Realised {
            Objects.requireNonNull(realisedOn, "realisedOn");
        }

        @Override
        public String name() {
            return "Realised";
        }
    }

    /**
     * Abandoned, with a reason. Kept as history but excluded from every projection, and terminal
     * like {@link Realised}: it declares no transition method.
     *
     * <p>The reason is held stripped of surrounding whitespace, so two cancellations that differ
     * only in it compare equal.
     */
    record Cancelled(LocalDate cancelledOn, String reason) implements PlanState {

        public Cancelled {
            Objects.requireNonNull(cancelledOn, "cancelledOn");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("Cancelling a plan requires a reason");
            }
            reason = reason.strip();
        }

        @Override
        public String name() {
            return "Cancelled";
        }
    }
}
