package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
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

    @Override
    public Optional<Account> findById(AccountId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public Optional<Account> findByName(String name) {
        Objects.requireNonNull(name, "name");
        // A scan. The chart of accounts is small by nature, and a second index here would be a
        // performance decision made on behalf of a store that has not been chosen.
        return accounts.values().stream()
                .filter(account -> account.name().equals(name))
                .findFirst();
    }

    @Override
    public void save(Account account) {
        Objects.requireNonNull(account, "account");
        accounts.put(account.id(), account);
    }
}
