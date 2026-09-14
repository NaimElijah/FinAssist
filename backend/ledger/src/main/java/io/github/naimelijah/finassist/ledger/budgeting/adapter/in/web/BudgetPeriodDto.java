package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * A budget period on the wire: an inclusive start and an exclusive end (ADR-028).
 *
 * <p>Used both ways, as {@code EntryDto} is in accounting: {@code @NotNull} on each member validates
 * it as part of a request and is read by springdoc as marking both members required in a response
 * too, so one class serves {@link DefineBudgetRequest} and {@link BudgetResponse} alike.
 *
 * <p>"Start before end" is left to {@link BudgetPeriod}'s own constructor, which throws {@link
 * IllegalArgumentException} — answered 422 by the composition fallback — rather than being duplicated
 * here as a class-level constraint.
 */
public record BudgetPeriodDto(
        @NotNull LocalDate start, @NotNull LocalDate end) {

    static BudgetPeriodDto from(BudgetPeriod period) {
        return new BudgetPeriodDto(period.start(), period.end());
    }

    /**
     * @throws IllegalArgumentException if {@code start} does not come before {@code end}
     */
    BudgetPeriod toBudgetPeriod() {
        return new BudgetPeriod(start, end);
    }
}
