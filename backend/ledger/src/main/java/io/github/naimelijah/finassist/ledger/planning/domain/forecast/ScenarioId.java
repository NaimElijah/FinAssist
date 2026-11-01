package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import java.util.Objects;
import java.util.UUID;

/** Identity of a scenario. A distinct type so it cannot be confused with a plan id. */
public record ScenarioId(UUID value) {

    /** @throws NullPointerException if {@code value} is null */
    public ScenarioId {
        Objects.requireNonNull(value, "value");
    }

    /** Generates a new random identity. Identity is minted inside the service only (ADR-021). */
    public static ScenarioId generate() {
        return new ScenarioId(UUID.randomUUID());
    }

    /**
     * Parses the canonical text form of a {@link UUID}.
     *
     * @throws IllegalArgumentException if {@code uuid} is not a valid UUID string
     * @throws NullPointerException if {@code uuid} is null
     */
    public static ScenarioId of(String uuid) {
        return new ScenarioId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
