package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How often something repeats.
 *
 * <p>A domain enum rather than {@link java.time.temporal.ChronoUnit}, which would also admit
 * nanoseconds, half-days and eras. Three cadences cover salary, rent and subscriptions, which is
 * what a household timeline is made of.
 */
public enum Cadence {
    WEEKLY,
    MONTHLY,
    YEARLY;

    /**
     * The date {@code periods} cadences after {@code start}.
     *
     * <p>Always computed from the original start rather than from the previous occurrence. The
     * difference shows at month ends: the 31st of January advanced one month at a time becomes 28
     * February and then 28 March, losing the 31st for good, while advancing from the start gives 28
     * February and then 31 March. {@link LocalDate#plusMonths} clamps to the last valid day of the
     * target month, which is the behaviour wanted — as long as it is applied to the start.
     *
     * @param start the date the first occurrence falls on
     * @param periods how many cadences after {@code start}; zero returns {@code start}
     * @return the date {@code periods} cadences after {@code start}
     * @throws java.time.DateTimeException if the result is outside the range {@link LocalDate} can
     *     hold
     */
    public LocalDate advance(LocalDate start, long periods) {
        return switch (this) {
            case WEEKLY -> start.plusWeeks(periods);
            case MONTHLY -> start.plusMonths(periods);
            case YEARLY -> start.plusYears(periods);
        };
    }

    /**
     * How many <strong>complete</strong> cadences separate two dates.
     *
     * <p>Not the inverse of {@link #advance}, and deliberately not treated as one: 31 January to 28
     * February is one month by {@code advance}, which clamps, and zero by this, which counts whole
     * periods. Callers that need the number of steps {@code advance} would take must check the
     * answer rather than trust it.
     *
     * @return the number of whole cadences from {@code start} to {@code end}; negative when
     *     {@code end} is before {@code start}
     */
    public long between(LocalDate start, LocalDate end) {
        return switch (this) {
            case WEEKLY -> ChronoUnit.WEEKS.between(start, end);
            case MONTHLY -> ChronoUnit.MONTHS.between(start, end);
            case YEARLY -> ChronoUnit.YEARS.between(start, end);
        };
    }
}
