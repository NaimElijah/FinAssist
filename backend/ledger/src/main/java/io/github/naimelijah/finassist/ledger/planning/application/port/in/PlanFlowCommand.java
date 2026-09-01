package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What a caller must supply to record an intention.
 *
 * <p>The amount is positive and the {@link Direction} carries the sign, which is the opposite of an
 * {@code Entry} and deliberately so (ADR-015). {@code PlannedFlow} refuses a negative amount; nothing
 * is re-checked here.
 */
public record PlanFlowCommand(
        String description, Money amount, Direction direction, LocalDate startsOn, Recurrence recurrence) {

    public PlanFlowCommand {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(recurrence, "recurrence");
    }
}
