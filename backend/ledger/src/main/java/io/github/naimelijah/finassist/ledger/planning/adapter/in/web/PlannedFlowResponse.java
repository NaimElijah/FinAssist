package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A planned flow as the store holds it: what it is for, how much, which way, when it starts, how often
 * it recurs and where its lifecycle has got to. Every member is always present, and marked required in
 * the contract.
 *
 * <p>{@code amount} is always positive and {@code direction} carries the sign (ADR-015). It is the
 * aggregate's own amount, passed through unchanged.
 */
public record PlannedFlowResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto amount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Direction direction,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The date of the first occurrence; every later one is counted from it.")
        LocalDate startsOn,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecurrenceResponse recurrence,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PlanStateResponse state) {

    static PlannedFlowResponse from(PlannedFlow flow) {
        return new PlannedFlowResponse(
                flow.id().value(),
                flow.description(),
                MoneyDto.from(flow.amount()),
                flow.direction(),
                flow.startsOn(),
                RecurrenceResponse.from(flow.recurrence()),
                PlanStateResponse.from(flow.state()));
    }
}
