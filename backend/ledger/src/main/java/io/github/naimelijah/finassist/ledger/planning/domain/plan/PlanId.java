package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import java.util.Objects;
import java.util.UUID;

/** Identity of a planned flow. A distinct type so it cannot be confused with any other id. */
public record PlanId(UUID value) {

    /** @throws NullPointerException if {@code value} is null */
    public PlanId {
        Objects.requireNonNull(value, "value");
    }

    /** Generates a new random identity. Identity is minted inside the service only (ADR-021). */
    public static PlanId generate() {
        return new PlanId(UUID.randomUUID());
    }

    /**
     * Parses the canonical text form of a {@link UUID}.
     *
     * @throws IllegalArgumentException if {@code uuid} is not a valid UUID string
     * @throws NullPointerException if {@code uuid} is null
     */
    public static PlanId of(String uuid) {
        return new PlanId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
