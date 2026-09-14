package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowCommand;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * What a client supplies to record an intention: what it is for, a positive amount, a direction, a start
 * date and a recurrence. It has no id, because identity is generated inside the service (ADR-021).
 *
 * <p>"Amount positive" and "recurrence ends after it starts" are left to {@code PlannedFlow}, which
 * throws {@link IllegalArgumentException} for each — answered 422 by the composition fallback — rather
 * than duplicated here. The amount is passed through as sent, never negated to fit a direction
 * (ADR-015).
 */
public record PlanFlowRequest(
        @NotBlank String description,
        @NotNull @Valid MoneyDto amount,
        @NotNull Direction direction,
        @NotNull LocalDate startsOn,
        @NotNull @Valid RecurrenceRequest recurrence) {

    /**
     * @throws IllegalArgumentException if the amount carries more precision than its currency admits, or
     *     the recurrence's interval or count is below one
     */
    PlanFlowCommand toCommand() {
        return new PlanFlowCommand(description, amount.toMoney(), direction, startsOn, recurrence.toRecurrence());
    }
}
