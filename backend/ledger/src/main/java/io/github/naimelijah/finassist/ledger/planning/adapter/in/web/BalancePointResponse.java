package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.BalancePoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * What the owner is projected to hold on one step boundary: cash, invested, and the two together. Every
 * member is always present, and marked required in the contract.
 *
 * <p>{@code cash} may be negative, which is a projected overdraft. {@code total} is {@link
 * BalancePoint#total()}, derived on every call and never stored, and legitimate only because both amounts
 * are in one currency (ADR-016).
 */
public record BalancePointResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate date,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto cash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto invested,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto total) {

    static BalancePointResponse from(BalancePoint point) {
        return new BalancePointResponse(
                point.date(),
                MoneyDto.from(point.cash()),
                MoneyDto.from(point.invested()),
                MoneyDto.from(point.total()));
    }
}
