package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

/**
 * An account of that name is already open.
 *
 * <p>Uniqueness is a property of the set, not of the value, so no constructor can check it and the
 * requirement sat unenforceable until there was a repository to ask. See ADR-021.
 */
public class DuplicateAccountNameException extends RuntimeException {

    public DuplicateAccountNameException(String name) {
        super("An account named '" + name + "' is already open; names are unique in the chart of accounts");
    }
}
