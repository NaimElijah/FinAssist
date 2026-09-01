package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;

/** Names a strategy: a set of intentions plus an assumption about growth. */
public interface DefineScenarioUseCase {

    /**
     * @return the stored scenario, carrying its generated identity
     * @throws PlanNotFoundException if a named flow does not exist
     * @throws IllegalArgumentException from the domain when a flow is in another currency, the same
     *     flow is listed twice, or the scenario has no name
     */
    Scenario define(DefineScenarioCommand command);
}
