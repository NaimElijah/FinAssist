package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The chart of accounts, in PostgreSQL.
 *
 * <p>{@link #save} is an upsert: saving an identity already stored rewrites its name, type and
 * currency. Nothing in the domain changes any of them today, so no caller asks for a rewrite, but
 * this class does not refuse one. The database refuses one kind: a change of currency once an entry
 * is posted to the account, through {@code journal_entry}'s foreign key on {@code (account_id,
 * currency)}, which surfaces from {@link #save} as a {@code DataIntegrityViolationException}. A
 * rewritten name or type is stored. None of this is an append-only guarantee of the kind ADR-020
 * attaches to the journal.
 */
public class JdbcAccountRepository implements AccountRepository {

    private static final String TABLE = "accounting.account";

    /**
     * Re-saving the same account is accepted; a different account claiming its name is not.
     *
     * <p>{@code ON CONFLICT (id)} handles only the first. A second account whose normalised name is
     * already held conflicts on {@code account_normalised_name_key} instead, which this clause does
     * not swallow, so the index still refuses it and {@link #save} still translates that refusal.
     */
    private static final String UPSERT = """
            INSERT INTO accounting.account (id, name, normalised_name, type, currency)
            VALUES (:id, :name, :normalisedName, :type, :currency)
            ON CONFLICT (id) DO UPDATE
               SET name            = EXCLUDED.name,
                   normalised_name = EXCLUDED.normalised_name,
                   type            = EXCLUDED.type,
                   currency        = EXCLUDED.currency
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, name, type, currency FROM accounting.account WHERE id = :id
            """;

    private static final String SELECT_BY_NORMALISED_NAME = """
            SELECT id, name, type, currency FROM accounting.account WHERE normalised_name = :normalisedName
            """;

    /** {@code AccountRepository.all}'s contract: every account, ordered by name ignoring case. */
    private static final String SELECT_ALL = """
            SELECT id, name, type, currency FROM accounting.account ORDER BY normalised_name
            """;

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        Objects.requireNonNull(id, "id");
        return jdbc.sql(SELECT_BY_ID)
                .param("id", id.value())
                .query(JdbcAccountRepository::toAccount)
                .optional();
    }

    @Override
    public Optional<Account> findByName(String name) {
        Objects.requireNonNull(name, "name");
        return jdbc.sql(SELECT_BY_NORMALISED_NAME)
                .param("normalisedName", AccountingRows.normalise(name))
                .query(JdbcAccountRepository::toAccount)
                .optional();
    }

    @Override
    public List<Account> all() {
        return jdbc.sql(SELECT_ALL).query(JdbcAccountRepository::toAccount).list();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uniqueness is the unique index, not a query. A check followed by a write is not a rule -
     * two callers can pass the check together - so the name is claimed by the insert and the
     * database's refusal is translated into the domain's exception.
     */
    @Override
    public void save(Account account) {
        Objects.requireNonNull(account, "account");
        try {
            jdbc.sql(UPSERT)
                    .param("id", account.id().value())
                    .param("name", account.name())
                    .param("normalisedName", AccountingRows.normalise(account.name()))
                    .param("type", account.type().name())
                    .param("currency", account.currency().name())
                    .update();
        } catch (DuplicateKeyException alreadyTaken) {
            throw new DuplicateAccountNameException(account.name());
        }
    }

    private static Account toAccount(ResultSet row, int rowNumber) throws SQLException {
        return new Account(
                new AccountId(row.getObject("id", java.util.UUID.class)),
                row.getString("name"),
                AccountingRows.enumerated(AccountType.class, TABLE, "type", row.getString("type")),
                AccountingRows.currency(row, TABLE, "currency"));
    }
}
