package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import java.util.Objects;
import java.util.UUID;

/** Identity of a planned flow. A distinct type so it cannot be confused with any other id. */
public record PlanId(UUID value) {

    public PlanId {
        Objects.requireNonNull(value, "value");
    }

    public static PlanId generate() {
        return new PlanId(UUID.randomUUID());
    }

    public static PlanId of(String uuid) {
        return new PlanId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
