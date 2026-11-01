package io.github.naimelijah.finassist.ledger.accounting.domain.account;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an account.
 *
 * <p>A wrapper rather than a bare {@link UUID} so that an account id cannot be passed where a
 * transaction id is expected. The compiler catches the mix-up that a bare UUID would not.
 *
 * <p>It does <strong>not</strong> leave the accounting context.
 * {@code LedgerArchitectureTest.contexts_do_not_depend_on_each_other} admits no exception, so
 * budgeting and planning name no accounting type at all - not an {@link Account}, and not an
 * {@code AccountId} either. A context that must point at an account declares its own reference type
 * and has it translated at the boundary, which is what planning's {@code AccountRef} does. See
 * ADR-021 and ADR-022.
 */
public record AccountId(UUID value) {

    /** @throws NullPointerException if {@code value} is null */
    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    /** Generates a new random identity. Identity is minted inside the service only (ADR-021). */
    public static AccountId generate() {
        return new AccountId(UUID.randomUUID());
    }

    /**
     * Parses the canonical text form of a {@link UUID}.
     *
     * @throws IllegalArgumentException if {@code uuid} is not a valid UUID string
     * @throws NullPointerException if {@code uuid} is null
     */
    public static AccountId of(String uuid) {
        return new AccountId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
