package io.github.naimelijah.finassist.ledger.planning.application.port.out;

import java.util.Objects;

/**
 * Planning's own way of pointing at an account it may not name.
 *
 * <p>ADR-015 predicted this type and deferred it: "Add a local {@code AccountRef} and a
 * realising-transaction reference the first time the application layer genuinely needs them, not
 * before." Projecting needs an opening balance, an opening balance comes from accounts, and
 * {@code contexts_do_not_depend_on_each_other} forbids planning from naming an {@code AccountId} at
 * any layer. So the trigger has fired, and this is the type it named.
 *
 * <p>It is an opaque string on purpose. Planning does not know that accounting identifies accounts
 * with a UUID and must not start depending on it; the translation is the composition root's job
 * (ADR-022). The realising-transaction reference is the other half of ADR-015's sentence and is still
 * deferred — nothing needs it yet.
 *
 * <p>The value is held stripped of surrounding whitespace, so two references that differ only in it
 * are equal.
 */
public record AccountRef(String value) {

    public AccountRef {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("An account reference must not be blank");
        }
        value = value.strip();
    }

    public static AccountRef of(String value) {
        return new AccountRef(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
