package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import java.util.Optional;

/** Where named strategies are kept. No delete, like every repository port here. */
public interface ScenarioRepository {

    Optional<Scenario> findById(ScenarioId id);

    void save(Scenario scenario);
}
