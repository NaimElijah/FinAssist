package io.github.naimelijah.finassist.ledger.accounting.application.port.out;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import java.util.List;
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
     * <p><strong>Matching ignores surrounding whitespace and case.</strong> A chart of accounts holding
     * both "Groceries" and "groceries" is a mistake, not a distinction: every balance and every journal
     * query would split between them, and a person reading a report would see one name twice.
     * {@code Category} makes the same choice in {@code budgeting}, and says the same thing about it.
     */
    Optional<Account> findByName(String name);

    /**
     * Every account, ordered by name ignoring case — {@code ORDER BY normalised_name}, the same
     * uniquely indexed column {@link #findByName} matches on.
     *
     * <p>A stable order is what makes a listing reproducible. Two calls with no write between them
     * must read the same as far as a caller can tell; an order nothing promised is one a query
     * planner is free to change between them, and a UI rendering the difference as a reorder would
     * be showing a defect rather than a fact about the chart of accounts.
     */
    List<Account> all();

    /**
     * Stores the account, replacing any earlier state of the same identity.
     *
     * <p><strong>Implementations enforce name uniqueness themselves</strong>, and must do it atomically.
     * {@code AccountService} checks first so that the ordinary path fails with a message naming the
     * account, but a check followed by a write is not a rule - two callers can pass the check together
     * and both insert. The durable adapter carries a unique index on the normalised name.
     *
     * @throws io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException
     *     if a different account already holds the name
     */
    void save(Account account);
}
