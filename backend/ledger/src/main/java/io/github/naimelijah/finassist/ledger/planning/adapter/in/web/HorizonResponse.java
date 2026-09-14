package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * The span a projection covers: {@code from} inclusive, {@code to} exclusive for the flows it counts,
 * though its points report the state at both ends (ADR-016). Both members are always present, and marked
 * required in the contract.
 */
public record HorizonResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate from,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate to) {

    static HorizonResponse from(Horizon horizon) {
        return new HorizonResponse(horizon.from(), horizon.to());
    }
}
