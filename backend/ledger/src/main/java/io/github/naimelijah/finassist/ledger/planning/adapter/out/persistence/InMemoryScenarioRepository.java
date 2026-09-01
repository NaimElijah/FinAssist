package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A development seam, not the store. See {@link InMemoryPlannedFlowRepository}. */
public class InMemoryScenarioRepository implements ScenarioRepository {

    private final Map<ScenarioId, Scenario> scenarios = new ConcurrentHashMap<>();

    @Override
    public Optional<Scenario> findById(ScenarioId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(scenarios.get(id));
    }

    @Override
    public void save(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        scenarios.put(scenario.id(), scenario);
    }
}
