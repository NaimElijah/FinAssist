package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import java.util.List;
import java.util.Optional;

/** Where named strategies are kept. No delete, like every repository port here. */
public interface ScenarioRepository {

    Optional<Scenario> findById(ScenarioId id);

    /**
     * Every scenario, ordered by name then id — {@code ORDER BY name, id}. Names are not unique, so
     * id is what separates two scenarios sharing one and keeps the order stable.
     */
    List<Scenario> all();

    /** Stores the scenario, replacing any earlier state of the same identity. */
    void save(Scenario scenario);
}
