package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import java.time.LocalDate;

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
     */
    public LocalDate advance(LocalDate start, long periods) {
        return switch (this) {
            case WEEKLY -> start.plusWeeks(periods);
            case MONTHLY -> start.plusMonths(periods);
            case YEARLY -> start.plusYears(periods);
        };
    }
}
