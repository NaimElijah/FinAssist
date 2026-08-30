package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The span a projection covers. Half-open: {@code from} inclusive, {@code to} exclusive, so
 * consecutive horizons tile without overlapping and a date belongs to exactly one of them.
 *
 * <p>The same shape as {@code budgeting.BudgetPeriod}, and deliberately not the same type: a budget
 * period and a forecasting horizon are different ideas in different contexts, and sharing one class
 * between them is the coupling the architecture rules exist to prevent.
 */
public record Horizon(LocalDate from, LocalDate to) {

    public Horizon {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "A horizon must start before it ends, but " + from + " is not before " + to);
        }
    }
}
