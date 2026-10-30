package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The span a projection covers. Half-open: {@code from} inclusive, {@code to} exclusive, so
 * consecutive horizons tile without overlapping and a date belongs to exactly one of them.
 *
 * <p>The same shape as {@code budgeting.BudgetPeriod}, and deliberately not the same type: a budget
 * period and a forecasting horizon are different ideas in different contexts, and sharing one class
 * between them is the coupling the architecture rules exist to prevent.
 *
 * <p><strong>A horizon spans at most {@value #MAXIMUM_SPAN_YEARS} years.</strong> A projection computes
 * and holds every step of its horizon at once, so an unbounded span is an unbounded allocation. The
 * bound is checked here, as the horizon is built, so it holds before any step is counted or advanced.
 */
public record Horizon(LocalDate from, LocalDate to) {

    /** The longest span a horizon may cover, in years: a lifetime of planning, and no more. */
    public static final int MAXIMUM_SPAN_YEARS = 100;

    /**
     * @throws IllegalArgumentException if {@code from} is not before {@code to}, or if the span is longer
     *     than {@value #MAXIMUM_SPAN_YEARS} years
     */
    public Horizon {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "A horizon must start before it ends, but " + from + " is not before " + to);
        }
        if (exceedsMaximumSpan(from, to)) {
            throw new IllegalArgumentException("A horizon spans at most " + MAXIMUM_SPAN_YEARS + " years, but " + from
                    + " to " + to + " is longer");
        }
    }

    /**
     * Whether {@code to} falls after {@code from} plus the maximum span. The whole years are counted first,
     * because {@code from.plusYears} overflows near {@link LocalDate#MAX}; only a span of exactly the
     * maximum in whole years needs the date itself, and then {@code from.plusYears} is at most {@code to}.
     */
    private static boolean exceedsMaximumSpan(LocalDate from, LocalDate to) {
        long wholeYears = ChronoUnit.YEARS.between(from, to);
        return wholeYears > MAXIMUM_SPAN_YEARS
                || wholeYears == MAXIMUM_SPAN_YEARS && to.isAfter(from.plusYears(MAXIMUM_SPAN_YEARS));
    }
}
