package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;

/** Reads a projected timeline of cash and invested balances. */
public interface ProjectScenarioUseCase {

    /**
     * Projects the scenario across the horizon.
     *
     * <p>Derived on every call and stored nowhere, exactly as a balance is. The flows are re-read from
     * the repository first, so a plan cancelled since the scenario was defined stops counting — which
     * is what {@code PlannedFlow.countsTowardsProjection} exists to decide.
     *
     * @throws ScenarioNotFoundException if no such scenario exists
     * @throws PlanNotFoundException if the scenario names a flow that no longer resolves
     * @throws IllegalArgumentException when the horizon is not a whole number of steps, or an
     *     account reference is refused as {@code OpeningPositionProvider} describes: not an
     *     account, unknown, or held in another currency than the scenario's
     */
    Projection project(ProjectScenarioCommand command);
}
