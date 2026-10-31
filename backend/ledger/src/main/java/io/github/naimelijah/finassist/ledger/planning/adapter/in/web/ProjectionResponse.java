package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A scenario projected across a horizon: every step boundary from {@code horizon.from} to {@code
 * horizon.to} inclusive, in one currency, and the first boundary at which cash is negative (ADR-016).
 *
 * <p>Derived on every request and stored nowhere. {@code scenario} is the id the request named, because a
 * {@link Projection} carries none of its own.
 *
 * <p>{@code firstShortfall} is omitted when cash never goes negative, rather than written as null, and is
 * the one optional member; the contract marks every other required. It sees only step boundaries: a dip
 * that recovers within one step is never reported, which a finer {@code step} narrows and nothing closes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID scenario,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HorizonResponse horizon,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cadence step,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currency currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<BalancePointResponse> points,

        @Schema(
                description = "The date of the first point at which projected cash is negative; absent when"
                        + " none is. Only points are checked, so a dip that recovers within one step is not"
                        + " reported.")
        LocalDate firstShortfall) {

    static ProjectionResponse from(ScenarioId scenario, Projection projection) {
        return new ProjectionResponse(
                scenario.value(),
                HorizonResponse.from(projection.horizon()),
                projection.step(),
                projection.currency(),
                projection.points().stream().map(BalancePointResponse::from).toList(),
                projection.firstShortfall().orElse(null));
    }
}
