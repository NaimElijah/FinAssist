package io.github.naimelijah.finassist.ledger.accounting.domain.account;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an account.
 *
 * <p>A wrapper rather than a bare {@link UUID} so that an account id cannot be passed where a
 * transaction id is expected. The compiler catches the mix-up that a bare UUID would not.
 *
 * <p>This is also the only shape of an account that leaves the accounting context: budgeting holds
 * {@code AccountId} values and never an {@link Account}, which is what
 * {@code LedgerArchitectureTest.contexts_do_not_depend_on_each_other} enforces.
 */
public record AccountId(UUID value) {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId generate() {
        return new AccountId(UUID.randomUUID());
    }

    public static AccountId of(String uuid) {
        return new AccountId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
