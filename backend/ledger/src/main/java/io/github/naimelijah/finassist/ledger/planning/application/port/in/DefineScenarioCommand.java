package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.GrowthAssumption;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.List;
import java.util.Objects;

/**
 * What a caller must supply to define a named strategy.
 *
 * <p>Flows are named by identity rather than passed by value, so a caller cannot hand in a
 * {@code PlannedFlow} that was never stored, or one whose lifecycle state has since moved on. The
 * service resolves each against the repository.
 *
 * <p>An empty list is legal: that is the do-nothing baseline, which a comparison needs in order to
 * have something to compare against.
 */
public record DefineScenarioCommand(String name, Currency currency, List<PlanId> flows, GrowthAssumption growth) {

    public DefineScenarioCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(growth, "growth");
        flows = List.copyOf(flows);
    }
}
