package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A development seam, not the store. See {@link InMemoryAccountRepository}.
 *
 * <p>Both queries return their results ordered by date and then by identity. The order is not
 * incidental: a projection and a budget review both fold over these lists, and a fold whose result
 * depends on a hash map's iteration order is a test that passes until it does not.
 */
public class InMemoryTransactionRepository implements TransactionRepository {

    private static final Comparator<Transaction> BY_DATE_THEN_ID = Comparator.comparing(Transaction::occurredOn)
            .thenComparing(transaction -> transaction.id().value());

    private final Map<TransactionId, Transaction> transactions = new ConcurrentHashMap<>();

    @Override
    public Optional<Transaction> findById(TransactionId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(transactions.get(id));
    }

    @Override
    public List<Transaction> findByAccount(AccountId account) {
        Objects.requireNonNull(account, "account");
        return transactions.values().stream()
                .filter(transaction -> touches(transaction, account))
                .sorted(BY_DATE_THEN_ID)
                .toList();
    }

    @Override
    public List<Transaction> findBetween(LocalDate fromInclusive, LocalDate toExclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        return transactions.values().stream()
                .filter(transaction -> !transaction.occurredOn().isBefore(fromInclusive)
                        && transaction.occurredOn().isBefore(toExclusive))
                .sorted(BY_DATE_THEN_ID)
                .toList();
    }

    @Override
    public void save(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        transactions.put(transaction.id(), transaction);
    }

    private static boolean touches(Transaction transaction, AccountId account) {
        for (Entry entry : transaction.entries()) {
            if (entry.account().equals(account)) {
                return true;
            }
        }
        return false;
    }
}
