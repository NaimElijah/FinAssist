package io.github.naimelijah.finassist.ledger.budgeting.domain.budget;

import java.util.Objects;
import java.util.UUID;

/** Identity of a budget. */
public record BudgetId(UUID value) {

    public BudgetId {
        Objects.requireNonNull(value, "value");
    }

    /** Creates a new identity from a random UUID. */
    public static BudgetId generate() {
        return new BudgetId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
