package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
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
 * <p>A stored value that cannot be read back is an {@link IllegalStateException} naming where it sits,
 * never the {@link IllegalArgumentException} the domain raises. The store holding a bad row is
 * a server fault; letting the domain's exception escape would have the problem handler answer it as a
 * rule the caller broke, with a 422 and no log line (ADR-028).
 *
 * <p>That log line is at ERROR, and ECS prints every cause's message into its stack trace. So a stored
 * value is quoted only when it is an enum discriminator; an amount is named by its row's key, and an
 * aggregate the domain refuses by its id, instead.
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
     * @param rowKey the row's key, such as {@code transaction_id=…, ordinal=0}, which a failure names in
     *     place of the amount
     * @throws IllegalStateException if the stored amount carries more precision than the currency
     *     admits, which {@link Money} refuses rather than rounds, or the currency is unknown
     */
    static Money money(ResultSet row, String table, String rowKey, String amountColumn, String currencyColumn)
            throws SQLException {
        Currency currency = currency(row, table, currencyColumn);
        try {
            return new Money(row.getBigDecimal(amountColumn), currency);
        } catch (IllegalArgumentException refused) {
            // Not chained: Money's message repeats the amount, and the key is enough to find the row.
            throw new IllegalStateException("Stored " + table + "." + amountColumn + " at " + rowKey
                    + " is not a valid " + currency + " amount and cannot be read back");
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

    /**
     * Rows that each read back, and together make an aggregate the domain refuses. No single column is
     * at fault, so the aggregate and the tables it spans are named instead.
     *
     * <p>The domain's refusal is deliberately not carried, as message or cause: it quotes what the owner
     * wrote, such as an account's name, and the aggregate's id is enough to find the rows.
     */
    static IllegalStateException unreconstructable(String aggregate, Object id, String tables) {
        return new IllegalStateException("Stored " + aggregate + " " + id + " in " + tables
                + " cannot be reconstructed: the domain refuses its rows together");
    }
}
