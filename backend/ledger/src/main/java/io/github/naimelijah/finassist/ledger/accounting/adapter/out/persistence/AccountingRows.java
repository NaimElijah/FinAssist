package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Turns rows into accounting's vocabulary and back.
 *
 * <p>Duplicated in each context rather than shared, deliberately. The slice rule matches {@code
 * ledger.(*)..} with no layer qualifier, so a common {@code ledger.persistence} package would be a
 * slice every context depends on and would fail the build; {@code shared} is exempt only as a
 * target, and ADR-021 already refused to let coupling accumulate there. The conversion is two lines
 * each way, which costs less than either (ADR-024).
 */
final class AccountingRows {

    private AccountingRows() {}

    /**
     * The form the unique index is built on.
     *
     * <p>Written here rather than computed by a generated column, because PostgreSQL's {@code
     * lower()} follows the database collation and Java's {@code toLowerCase(Locale.ROOT)} does not.
     * The index has to agree with {@code findByName} exactly, so one of them has to be the
     * authority, and it is this method.
     */
    static String normalise(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a monetary amount from an amount column and its currency column.
     *
     * @throws IllegalArgumentException if the stored amount carries more precision than the
     *     currency admits, which {@link Money} refuses rather than rounds
     */
    static Money money(ResultSet row, String amountColumn, String currencyColumn) throws SQLException {
        BigDecimal amount = row.getBigDecimal(amountColumn);
        return new Money(amount, currency(row, currencyColumn));
    }

    static Currency currency(ResultSet row, String column) throws SQLException {
        return Currency.valueOf(row.getString(column));
    }
}
