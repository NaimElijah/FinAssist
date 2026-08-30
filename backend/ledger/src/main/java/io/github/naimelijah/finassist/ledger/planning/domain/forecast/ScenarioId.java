package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import java.util.Objects;
import java.util.UUID;

/** Identity of a scenario. A distinct type so it cannot be confused with a plan id. */
public record ScenarioId(UUID value) {

    public ScenarioId {
        Objects.requireNonNull(value, "value");
    }

    public static ScenarioId generate() {
        return new ScenarioId(UUID.randomUUID());
    }

    public static ScenarioId of(String uuid) {
        return new ScenarioId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
