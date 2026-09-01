package io.github.naimelijah.finassist.ledger.accounting.application.port.out;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import java.util.Optional;

/**
 * Where accounts are kept, in accounting's own words.
 *
 * <p><strong>There is no delete.</strong> The chart of accounts is part of the journal, and ADR-020
 * makes the journal append-only: an account with entries posted against it cannot be removed without
 * falsifying them. The absence is the enforcement — a durable adapter inherits a contract that cannot
 * express a destructive write, so honouring ADR-020 is not left to a schema to remember. See ADR-021.
 */
public interface AccountRepository {

    Optional<Account> findById(AccountId id);

    /**
     * Looks an account up by name, for the uniqueness check in {@code AccountService}.
     *
     * @param name already normalised the way {@link Account} normalises it, so that " Cash" and "Cash"
     *     do not both find nothing and both insert
     */
    Optional<Account> findByName(String name);

    /** Stores the account, replacing any earlier state of the same identity. */
    void save(Account account);
}
