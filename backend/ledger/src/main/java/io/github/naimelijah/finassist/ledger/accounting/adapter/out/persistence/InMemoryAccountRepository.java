package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A development seam, not the store.
 *
 * <p>ADR-006 (PostgreSQL) is still Proposed and this does not pre-empt it: there is no driver on the
 * classpath, no schema and no SQL. What this exists for is to make the application layer runnable and
 * the wiring real while the store is still an open decision. It is replaced, not grown.
 *
 * <p>It holds aggregates directly rather than mapping to an entity, which is the one thing a real
 * adapter must not do — JPA's no-arg constructors and mutable fields are exactly what
 * {@code domain_depends_on_no_framework} keeps out of the domain, and mapping is what that costs.
 * Nothing here should be read as a template for that.
 */
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();

    /**
     * Claims a normalised name for an account id. Not an optimisation - it is what makes uniqueness
     * atomic. A scan plus a write is a check-then-act, and two callers can pass the check together.
     */
    private final Map<String, AccountId> byName = new ConcurrentHashMap<>();

    @Override
    public Optional<Account> findById(AccountId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public Optional<Account> findByName(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(byName.get(normalise(name))).map(accounts::get);
    }

    @Override
    public void save(Account account) {
        Objects.requireNonNull(account, "account");

        AccountId holder = byName.putIfAbsent(normalise(account.name()), account.id());
        if (holder != null && !holder.equals(account.id())) {
            throw new DuplicateAccountNameException(account.name());
        }
        accounts.put(account.id(), account);
    }

    /**
     * The form names are compared in. Case-folded as well as stripped, because a chart holding both
     * "Groceries" and "groceries" splits every balance between two accounts a person reads as one -
     * the failure {@code Category} normalises for the same way, and says so.
     *
     * <p>There is no rename, so a stale claim cannot accumulate here. Adding one means releasing the
     * old name in the same atomic step, which is a change to this class and to the port that permits it.
     */
    private static String normalise(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
