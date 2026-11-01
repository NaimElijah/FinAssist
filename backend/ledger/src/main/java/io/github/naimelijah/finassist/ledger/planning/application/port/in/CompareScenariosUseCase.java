package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;

/** Puts two or more strategies side by side over one horizon. */
public interface CompareScenariosUseCase {

    /**
     * Projects every named scenario from one opening position and holds the projections side by
     * side. Derived on every call and stored nowhere.
     *
     * @throws ScenarioNotFoundException if a named scenario does not exist
     * @throws PlanNotFoundException if a scenario names a flow that no longer resolves
     * @throws IllegalArgumentException when fewer than two or more than
     *     {@code Comparison.MAXIMUM_SCENARIOS} scenarios are given, two share a name (one scenario
     *     named twice included), they are in different currencies, the horizon is not a whole
     *     number of steps, or an account reference is refused as {@code OpeningPositionProvider}
     *     describes
     */
    Comparison compare(CompareScenariosCommand command);
}
