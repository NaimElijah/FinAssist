package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Divergence;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * One scenario within a comparison: its id and name, its projected points, where it ends up, and how far
 * it drifts from the baseline at each point. Every member is always present, and marked required in the
 * contract.
 *
 * <p>{@code endState} repeats the last point on purpose: it is the figure a comparison usually turns on,
 * and naming it saves every client finding the last element. The baseline's own {@code divergence} is an
 * empty list rather than a series of zeros, because the domain leaves the baseline out of its divergences:
 * its distance from itself is noise.
 */
public record ComparedScenarioResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<BalancePointResponse> points,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BalancePointResponse endState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DivergenceResponse> divergence) {

    static ComparedScenarioResponse from(
            ScenarioId id, String name, Projection projection, List<Divergence> divergence) {
        return new ComparedScenarioResponse(
                id.value(),
                name,
                projection.points().stream().map(BalancePointResponse::from).toList(),
                BalancePointResponse.from(projection.endState()),
                divergence.stream().map(DivergenceResponse::from).toList());
    }
}
