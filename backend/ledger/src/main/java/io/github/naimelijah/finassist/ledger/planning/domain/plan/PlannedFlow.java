package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The planning aggregate root: money the owner <strong>intends</strong> to move, and when.
 *
 * <p>A planned flow is not a {@code Transaction} and this package is not
 * {@code accounting.domain.entry}. The books record what happened; this records what is meant to
 * happen, and the two must never be summed together. Keeping them in separate bounded contexts is
 * what makes that mistake impossible rather than merely discouraged (ADR-011, ADR-015).
 *
 * <p>The amount is always positive and the {@link Direction} carries the sign. A planned purchase of
 * minus fifty is not a thing anyone means.
 *
 * <p>The flow names no account. An {@code AccountId} belongs to the accounting context and importing
 * one here would fail
 * {@code LedgerArchitectureTest.contexts_do_not_depend_on_each_other}. Planning's own
 * {@code AccountRef}, in {@code application.port.out}, addresses accounts only to open a
 * projection, and a plan carries none.
 */
public record PlannedFlow(
        PlanId id,
        String description,
        Money amount,
        Direction direction,
        LocalDate startsOn,
        Recurrence recurrence,
        PlanState state) {

    /**
     * Checks every rule of a planned flow; the description is held stripped of surrounding
     * whitespace.
     *
     * @throws NullPointerException if any component is null
     * @throws IllegalArgumentException if the description is blank, the amount is not positive, or
     *     an {@link Recurrence.Until} does not end after {@code startsOn}
     */
    public PlannedFlow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(recurrence, "recurrence");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(description, "description");

        if (description.isBlank()) {
            throw new IllegalArgumentException("A planned flow must say what it is for");
        }
        description = description.strip();

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A planned flow must be for a positive amount but was " + amount
                    + "; the direction carries the sign");
        }

        // Also checked in Until.occurrences, which is public API a caller can reach directly.
        // Repeating it here is deliberate: this refuses the plan at construction, where the mistake
        // was made, rather than at the projection that would otherwise be the first thing to notice.
        if (recurrence instanceof Recurrence.Until until && !until.until().isAfter(startsOn)) {
            throw new IllegalArgumentException("A recurrence cannot end on " + until.until() + ", before it starts on "
                    + startsOn + "; it would never occur");
        }
    }

    /**
     * Records an intention. It starts {@code Planned} — nothing has been decided yet — under a
     * newly generated identity.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException for the reasons the constructor gives
     */
    public static PlannedFlow plan(
            String description, Money amount, Direction direction, LocalDate startsOn, Recurrence recurrence) {
        return new PlannedFlow(
                PlanId.generate(), description, amount, direction, startsOn, recurrence, new PlanState.Planned());
    }

    /** The currency of {@link #amount()}, which is the currency of the whole flow. */
    public Currency currency() {
        return amount.currency();
    }

    /**
     * Whether this flow belongs in a projection of the future.
     *
     * <p>{@code Cancelled} is excluded because it will not happen. {@code Realised} is excluded
     * because it already has: the money moved, so it is in the opening balance a projection starts
     * from, and counting it again would spend it twice. Both are kept as history, neither is
     * forecast.
     */
    public boolean countsTowardsProjection() {
        return state instanceof PlanState.Planned || state instanceof PlanState.Committed;
    }

    /**
     * Every date this flow lands on within {@code [fromInclusive, toExclusive)}, ascending.
     *
     * <p>Answers from the recurrence alone and ignores the state, so a cancelled flow still reports
     * its dates; a caller building a forecast filters with {@link #countsTowardsProjection}.
     *
     * @throws IllegalArgumentException if {@code fromInclusive} is not before {@code toExclusive}
     * @throws NullPointerException if either bound is null
     */
    public List<LocalDate> occurrences(LocalDate fromInclusive, LocalDate toExclusive) {
        return recurrence.occurrences(startsOn, fromInclusive, toExclusive);
    }

    /**
     * Decides on the flow. Committing ahead of the date it is planned for is ordinary and allowed —
     * deciding in January to buy something in March is the normal case.
     *
     * @throws IllegalPlanTransitionException if the flow is not {@code Planned}
     * @throws NullPointerException if {@code committedOn} is null
     */
    public PlannedFlow commit(LocalDate committedOn) {
        if (!(state instanceof PlanState.Planned planned)) {
            throw new IllegalPlanTransitionException(state, "commit");
        }
        Objects.requireNonNull(committedOn, "committedOn");
        return withState(planned.commit(committedOn));
    }

    /**
     * Marks the money as actually moved, on a date that cannot precede the commitment.
     *
     * @throws IllegalPlanTransitionException if the flow is not {@code Committed}
     * @throws IllegalArgumentException if {@code realisedOn} is before the date it was committed
     * @throws NullPointerException if {@code realisedOn} is null
     */
    public PlannedFlow realise(LocalDate realisedOn) {
        if (!(state instanceof PlanState.Committed committed)) {
            throw new IllegalPlanTransitionException(state, "realise");
        }
        requireNotBefore(realisedOn, committed.committedOn(), "realised", "committed");
        return withState(committed.realise(realisedOn));
    }

    /**
     * Abandons the flow. Legal from {@code Planned} and {@code Committed} only — a realised flow
     * describes money that has already moved and cannot be un-spent by editing a plan.
     *
     * @throws IllegalPlanTransitionException if the flow is already {@code Realised} or
     *     {@code Cancelled}; the state is checked before either argument, as {@link #commit} and
     *     {@link #realise} check it
     * @throws IllegalArgumentException if {@code reason} is blank, or the flow is {@code Committed}
     *     and {@code cancelledOn} is before the date it was committed
     * @throws NullPointerException if {@code cancelledOn} or {@code reason} is null
     */
    public PlannedFlow cancel(LocalDate cancelledOn, String reason) {
        PlanState next = switch (state) {
            case PlanState.Planned planned -> planned.cancel(cancelledOn, reason);
            case PlanState.Committed committed -> {
                requireNotBefore(cancelledOn, committed.committedOn(), "cancelled", "committed");
                yield committed.cancel(cancelledOn, reason);
            }
            case PlanState.Realised ignored -> throw new IllegalPlanTransitionException(state, "cancel");
            case PlanState.Cancelled ignored -> throw new IllegalPlanTransitionException(state, "cancel");
        };
        return withState(next);
    }

    private PlannedFlow withState(PlanState next) {
        return new PlannedFlow(id, description, amount, direction, startsOn, recurrence, next);
    }

    /** A plan moves forward in time; a transition dated before the one it follows is not a state. */
    private static void requireNotBefore(LocalDate later, LocalDate earlier, String laterName, String earlierName) {
        Objects.requireNonNull(later, laterName);
        if (later.isBefore(earlier)) {
            throw new IllegalArgumentException("A plan cannot be " + laterName + " on " + later + ", before it was "
                    + earlierName + " on " + earlier);
        }
    }
}
