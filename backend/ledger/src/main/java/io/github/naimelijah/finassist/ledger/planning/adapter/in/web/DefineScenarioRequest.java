package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
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
 * GrowthAssumption} sees it; a rate at or below minus one, a flow in another currency and a flow listed
 * twice are the domain's {@link IllegalArgumentException}, answered 422 rather than duplicated here.
 */
public record DefineScenarioRequest(
        @NotBlank String name,
        @NotNull Currency currency,
        @NotNull List<@NotNull UUID> flows,
        @NotNull @Pattern(regexp = MoneyDto.DECIMAL_TEXT) String growthRatePerStep) {

    /**
     * @throws IllegalArgumentException if the growth rate is at or below minus one
     */
    DefineScenarioCommand toCommand() {
        return new DefineScenarioCommand(
                name, currency, flows.stream().map(PlanId::new).toList(), GrowthAssumption.perStep(growthRatePerStep));
    }
}
