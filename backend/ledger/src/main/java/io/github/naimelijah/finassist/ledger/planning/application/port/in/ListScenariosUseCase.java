package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import java.util.List;

/** Reads every named strategy. */
public interface ListScenariosUseCase {

    /** Every scenario, ordered by name then id — names are not unique, so id breaks the tie. */
    List<Scenario> all();
}
