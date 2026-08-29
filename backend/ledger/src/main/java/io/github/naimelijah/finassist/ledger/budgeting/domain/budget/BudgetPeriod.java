package io.github.naimelijah.finassist.ledger.budgeting.domain.budget;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * The span a budget covers. Half-open: {@code start} inclusive, {@code end} exclusive, so
 * consecutive periods tile without overlapping and a date belongs to exactly one of them.
 */
public record BudgetPeriod(LocalDate start, LocalDate end) {

    public BudgetPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException(
                    "A budget period must start before it ends, but " + start + " is not before " + end);
        }
    }

    /** The calendar month, the ordinary case. */
    public static BudgetPeriod of(YearMonth month) {
        Objects.requireNonNull(month, "month");
        return new BudgetPeriod(month.atDay(1), month.plusMonths(1).atDay(1));
    }

    public boolean contains(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(start) && date.isBefore(end);
    }
}
