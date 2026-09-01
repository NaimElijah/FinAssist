package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.Objects;

/**
 * What a caller must supply to open an account.
 *
 * <p>No identity: an id is generated inside the service, never accepted from outside, so a caller
 * cannot choose one and cannot collide with an existing account by accident.
 *
 * <p>The name is stripped here as well as in {@code Account}, which looks like duplication and is
 * not. The uniqueness check runs against the stored form, so it must normalise before it looks — with
 * the strip left to the aggregate, " Groceries" and "Groceries" would each find nothing and each be
 * inserted, and the rule this command exists to support would pass while being broken.
 *
 * <p>Case is <strong>not</strong> folded here, deliberately. The name is what a person reads on a
 * report, so "Groceries" is stored as typed; matching it against "groceries" is the repository's
 * business, and doing it here would make the display form depend on which spelling arrived first.
 */
public record OpenAccountCommand(String name, AccountType type, Currency currency) {

    public OpenAccountCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Account name must not be blank");
        }
        name = name.strip();
    }
}
