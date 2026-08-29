package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import java.util.Objects;
import java.util.UUID;

/** Identity of a transaction. A distinct type so it cannot be confused with an account id. */
public record TransactionId(UUID value) {

    public TransactionId {
        Objects.requireNonNull(value, "value");
    }

    public static TransactionId generate() {
        return new TransactionId(UUID.randomUUID());
    }

    public static TransactionId of(String uuid) {
        return new TransactionId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
