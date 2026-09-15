package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Divergence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * How far one scenario has drifted from the baseline on one step boundary, read as "this scenario minus
 * the baseline": a positive figure means the scenario is ahead. Every member is always present, and
 * marked required in the contract.
 *
 * <p>Its own type rather than a {@link BalancePointResponse}, as {@link Divergence} is not a {@code
 * BalancePoint}: a difference is not a position, and any member, {@code invested} included, may be
 * negative.
 */
public record DivergenceResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate date,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto cash,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto invested,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto total) {

    static DivergenceResponse from(Divergence divergence) {
        return new DivergenceResponse(
                divergence.date(),
                MoneyDto.from(divergence.cash()),
                MoneyDto.from(divergence.invested()),
                MoneyDto.from(divergence.total()));
    }
}
