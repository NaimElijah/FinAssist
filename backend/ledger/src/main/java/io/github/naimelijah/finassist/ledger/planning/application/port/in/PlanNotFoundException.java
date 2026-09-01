package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;

/** No planned flow of that identity has been recorded. */
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException(PlanId plan) {
        super("No planned flow with id " + plan);
    }
}
