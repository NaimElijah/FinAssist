package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import java.util.Objects;
import java.util.UUID;

/** Identity of a transaction. A distinct type so it cannot be confused with an account id. */
public record TransactionId(UUID value) {

    /** @throws NullPointerException if {@code value} is null */
    public TransactionId {
        Objects.requireNonNull(value, "value");
    }

    /** Generates a new random identity. Identity is minted inside the service only (ADR-021). */
    public static TransactionId generate() {
        return new TransactionId(UUID.randomUUID());
    }

    /**
     * Parses the canonical text form of a {@link UUID}.
     *
     * @throws IllegalArgumentException if {@code uuid} is not a valid UUID string
     * @throws NullPointerException if {@code uuid} is null
     */
    public static TransactionId of(String uuid) {
        return new TransactionId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
