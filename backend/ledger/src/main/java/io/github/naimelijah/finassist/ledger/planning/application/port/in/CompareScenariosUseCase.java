package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;

/** Puts two or more strategies side by side over one horizon. */
public interface CompareScenariosUseCase {

    /**
     * @throws ScenarioNotFoundException if a named scenario does not exist
     * @throws IllegalArgumentException from the domain when fewer than two scenarios are given, two
     *     share a name, or they disagree on currency, horizon or cadence
     */
    Comparison compare(CompareScenariosCommand command);
}
