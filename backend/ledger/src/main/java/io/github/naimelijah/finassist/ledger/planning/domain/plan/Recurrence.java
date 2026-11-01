package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How often a planned flow happens: once, or on a fixed cadence with an end.
 *
 * <p>A sealed interface rather than one record with a nullable {@code until} and a nullable
 * {@code count}. That shape admits two states nobody wants — both set, and neither set, the second
 * of which is an unbounded plan that no projection can terminate on. Here they are unrepresentable.
 *
 * <p>Occurrences are reported over a bare date range rather than a {@code Horizon}. A horizon is a
 * forecasting idea, and having a plan reach for one would point this package at
 * {@code planning.domain.forecast} and put a cycle between two aggregates —
 * {@code LedgerArchitectureTest.aggregates_are_free_of_cycles} fails the build for exactly that.
 */
public sealed interface Recurrence {

    /**
     * Every date this recurrence lands on within {@code [fromInclusive, toExclusive)}, in ascending
     * order.
     *
     * <p>Occurrences are always measured from {@code start}, never from the previous one; see
     * {@link Cadence#advance} for why that matters at month ends.
     *
     * @param start the date the first occurrence falls on
     */
    List<LocalDate> occurrences(LocalDate start, LocalDate fromInclusive, LocalDate toExclusive);

    /** Happens once, on its own date. */
    record Once() implements Recurrence {

        @Override
        public List<LocalDate> occurrences(LocalDate start, LocalDate fromInclusive, LocalDate toExclusive) {
            requireRange(start, fromInclusive, toExclusive);
            return within(start, fromInclusive, toExclusive) ? List.of(start) : List.of();
        }
    }

    /** Repeats every {@code interval} cadences, up to but not including {@code until}. */
    record Until(Cadence cadence, int interval, LocalDate until) implements Recurrence {

        public Until {
            Objects.requireNonNull(cadence, "cadence");
            Objects.requireNonNull(until, "until");
            requirePositiveInterval(interval);
        }

        @Override
        public List<LocalDate> occurrences(LocalDate start, LocalDate fromInclusive, LocalDate toExclusive) {
            requireRange(start, fromInclusive, toExclusive);
            // Refused rather than answered with an empty list. This is public API that a caller can
            // reach without going through PlannedFlow, and a recurrence ending before it begins is
            // a mistake in the plan, not a plan with nothing in it - returning [] would hide it
            // inside a projection that simply came out flat.
            if (!until.isAfter(start)) {
                throw new IllegalArgumentException("A recurrence cannot end on " + until + ", before it starts on "
                        + start + "; it would never occur");
            }
            long lastStep = lastStepBefore(cadence, start, toExclusive);
            List<LocalDate> dates = new ArrayList<>();
            for (long period = 0; period * interval <= lastStep; period++) {
                LocalDate date = cadence.advance(start, period * interval);
                if (!date.isBefore(until) || !date.isBefore(toExclusive)) {
                    return dates;
                }
                if (within(date, fromInclusive, toExclusive)) {
                    dates.add(date);
                }
            }
            return dates;
        }
    }

    /** Repeats every {@code interval} cadences, exactly {@code count} times in total. */
    record Times(Cadence cadence, int interval, int count) implements Recurrence {

        public Times {
            Objects.requireNonNull(cadence, "cadence");
            requirePositiveInterval(interval);
            if (count < 1) {
                throw new IllegalArgumentException("A recurrence must happen at least once but was to happen " + count
                        + " times; use Once for a single occurrence");
            }
        }

        @Override
        public List<LocalDate> occurrences(LocalDate start, LocalDate fromInclusive, LocalDate toExclusive) {
            requireRange(start, fromInclusive, toExclusive);
            long lastStep = lastStepBefore(cadence, start, toExclusive);
            List<LocalDate> dates = new ArrayList<>();
            for (long period = 0; period < count && period * interval <= lastStep; period++) {
                LocalDate date = cadence.advance(start, period * interval);
                if (!date.isBefore(toExclusive)) {
                    return dates;
                }
                if (within(date, fromInclusive, toExclusive)) {
                    dates.add(date);
                }
            }
            return dates;
        }
    }

    private static void requirePositiveInterval(int interval) {
        if (interval < 1) {
            throw new IllegalArgumentException(
                    "A recurrence interval must be at least one cadence but was " + interval);
        }
    }

    /**
     * An upper bound on the cadences from {@code start} that can still land before
     * {@code toExclusive}; the step at the bound itself may land on {@code toExclusive}.
     *
     * <p>A step past it is never computed. {@link Cadence#between} counts complete periods, and
     * {@link Cadence#advance} only clamps a day down to the end of a month that is then the end's own
     * month, so one more step always lands on or after {@code toExclusive}: stopping here drops no
     * occurrence. Computing that step regardless would let an interval of a billion years ask
     * {@link LocalDate} for a year it cannot hold, and throw instead of answer.
     */
    private static long lastStepBefore(Cadence cadence, LocalDate start, LocalDate toExclusive) {
        return cadence.between(start, toExclusive);
    }

    private static void requireRange(LocalDate start, LocalDate fromInclusive, LocalDate toExclusive) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "A range must start before it ends, but " + fromInclusive + " is not before " + toExclusive);
        }
    }

    private static boolean within(LocalDate date, LocalDate fromInclusive, LocalDate toExclusive) {
        return !date.isBefore(fromInclusive) && date.isBefore(toExclusive);
    }
}
