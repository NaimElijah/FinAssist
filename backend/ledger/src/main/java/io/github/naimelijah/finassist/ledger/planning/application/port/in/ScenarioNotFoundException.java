package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;

/** No scenario of that identity has been defined. */
public class ScenarioNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScenarioNotFoundException(ScenarioId scenario) {
        super("No scenario with id " + scenario);
    }
}
