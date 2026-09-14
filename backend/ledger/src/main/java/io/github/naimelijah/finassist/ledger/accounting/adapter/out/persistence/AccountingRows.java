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
 *
 * <p>A stored value that cannot be read back is an {@link IllegalStateException} naming its table and
 * column, never the {@link IllegalArgumentException} the domain raises. The store holding a bad row is
 * a server fault; letting the domain's exception escape would have the problem handler answer it as a
 * rule the caller broke, with a 422 and no log line (ADR-028).
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
     * @throws IllegalStateException if the stored amount carries more precision than the currency
     *     admits, which {@link Money} refuses rather than rounds, or the currency is unknown
     */
    static Money money(ResultSet row, String table, String amountColumn, String currencyColumn) throws SQLException {
        Currency currency = currency(row, table, currencyColumn);
        BigDecimal amount = row.getBigDecimal(amountColumn);
        try {
            return new Money(amount, currency);
        } catch (IllegalArgumentException refused) {
            throw unmappable(table, amountColumn, amount.toPlainString() + " " + currency, refused);
        }
    }

    /** @throws IllegalStateException if the stored code names no {@link Currency} */
    static Currency currency(ResultSet row, String table, String column) throws SQLException {
        return enumerated(Currency.class, table, column, row.getString(column));
    }

    /** @throws IllegalStateException if {@code stored} names no constant of {@code type} */
    static <E extends Enum<E>> E enumerated(Class<E> type, String table, String column, String stored) {
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException unknown) {
            throw unmappable(table, column, stored, unknown);
        }
    }

    /** A stored value the domain refused to take back. */
    static IllegalStateException unmappable(String table, String column, String stored, RuntimeException cause) {
        return new IllegalStateException(
                "Stored " + table + "." + column + " value '" + stored + "' cannot be read back: " + cause.getMessage(),
                cause);
    }

    /** A stored discriminator this class has no variant for. */
    static IllegalStateException unmappable(String table, String column, String stored) {
        return new IllegalStateException(
                "Stored " + table + "." + column + " value '" + stored + "' names no known variant");
    }
}
