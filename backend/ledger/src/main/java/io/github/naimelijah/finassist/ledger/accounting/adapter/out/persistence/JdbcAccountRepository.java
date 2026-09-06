package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The chart of accounts, in PostgreSQL.
 *
 * <p>An account is written once and never rewritten: nothing in the domain changes a name, a type
 * or a currency, so this class holds an insert and two queries and no update. That is not an
 * append-only guarantee of the kind ADR-020 attaches to the journal - it is simply all the
 * behaviour there is.
 */
public class JdbcAccountRepository implements AccountRepository {

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
                AccountType.valueOf(row.getString("type")),
                AccountingRows.currency(row, "currency"));
    }
}
