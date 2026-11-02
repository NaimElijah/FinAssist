package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

/**
 * What a client supplies to define a scenario: a name, a currency, the ids of recorded flows it assumes,
 * and a growth rate per projection step. It has no id, because identity is generated inside the service
 * (ADR-021).
 *
 * <p>{@code flows} may be empty: that is the do-nothing baseline a comparison needs. The growth rate is
 * decimal text held to the same pattern as an amount, so an exponent is a 400 before {@link
 * GrowthAssumption} sees it; a rate at or below minus one or above one, a rate with more than ten
 * decimal places (ADR-072), a flow in another currency and a flow listed twice are the domain's {@link
 * IllegalArgumentException}, answered 422 rather than duplicated here.
 */
public record DefineScenarioRequest(
        @NotBlank String name,
        @NotNull Currency currency,
        @NotNull List<@NotNull UUID> flows,

        @Schema(description = GROWTH_RATE) @NotNull @Pattern(regexp = MoneyDto.DECIMAL_TEXT)
        String growthRatePerStep) {

    /**
     * What the contract says about the rate's bounds. The upper bound is written out because {@code
     * GrowthAssumption.MAXIMUM_RATE} is a {@code BigDecimal}, which no annotation can read; keep the two
     * in step.
     */
    static final String GROWTH_RATE = "The growth of the invested balance over one projection step, as a"
            + " fraction: 0.05 is 5% a step. Greater than -1 and at most 1, which doubles the balance, with at"
            + " most " + GrowthAssumption.MAXIMUM_DECIMAL_PLACES + " decimal places.";

    /**
     * @throws IllegalArgumentException if the growth rate is at or below minus one, above one, or has
     *     more than ten decimal places
     */
    DefineScenarioCommand toCommand() {
        return new DefineScenarioCommand(
                name, currency, flows.stream().map(PlanId::new).toList(), GrowthAssumption.perStep(growthRatePerStep));
    }
}
