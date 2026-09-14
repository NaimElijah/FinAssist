package io.github.naimelijah.finassist.ledger.budgeting.domain.budget;

import java.util.Objects;
import java.util.UUID;

/** Identity of a budget. */
public record BudgetId(UUID value) {

    public BudgetId {
        Objects.requireNonNull(value, "value");
    }

    public static BudgetId generate() {
        return new BudgetId(UUID.randomUUID());
    }

    public static BudgetId of(String uuid) {
        return new BudgetId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
