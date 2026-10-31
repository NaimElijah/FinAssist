package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * A named strategy: its id, name and currency, the ids of the flows it assumes, and its growth rate per
 * projection step. Every member is always present, and marked required in the contract.
 *
 * <p>{@code flows} carries ids, not the flows themselves: {@code GET /planned-flows} holds those, and a
 * copy here would describe their state as it was when the scenario was stored, which a projection does
 * not use.
 *
 * <p>{@code growthRatePerStep} is decimal text, like every decimal on the wire (ADR-028), and is written
 * with {@link java.math.BigDecimal#toPlainString()}. {@code GrowthAssumption} strips trailing zeros, which
 * is exactly what makes {@code toString} reach for an exponent — {@code 1E-7}, or {@code 1E+1} for ten —
 * and that text would fail the pattern a define request is held to. The contract publishes that pattern
 * here too, so a client reads one shape for the rate it sends and the rate it gets back.
 */
public record ScenarioResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currency currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> flows,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = MoneyDto.DECIMAL_TEXT)
        String growthRatePerStep) {

    static ScenarioResponse from(Scenario scenario) {
        return new ScenarioResponse(
                scenario.id().value(),
                scenario.name(),
                scenario.currency(),
                scenario.flows().stream().map(flow -> flow.id().value()).toList(),
                scenario.growth().ratePerStep().toPlainString());
    }
}
