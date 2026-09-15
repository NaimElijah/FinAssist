package io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Turns rows into budgeting's vocabulary, and says where a row that cannot be read back sits.
 *
 * <p>Duplicated in each context rather than shared, for the reason {@code AccountingRows} gives: the
 * slice rule leaves no legal shared home, and ADR-021 refused to let coupling accumulate in {@code
 * shared}.
 *
 * <p>A stored value that cannot be read back is an {@link IllegalStateException}, never the {@link
 * IllegalArgumentException} the domain raises. The store holding a bad row is a server fault; letting
 * the domain's exception escape would have the problem handler answer it as a rule the caller broke,
 * with a 422 and no log line (ADR-028).
 *
 * <p>That log line is at ERROR, and ECS prints every cause's message into its stack trace. So a stored
 * value is quoted only when it is an enum discriminator; an amount is named by its row's key, and a
 * budget the domain refuses by its id, instead.
 */
final class BudgetingRows {

    private BudgetingRows() {}

    /**
     * Reads a monetary amount from an amount column and its currency column.
     *
     * @param rowKey the row's key, such as {@code budget_id=…}, which a failure names in place of the
     *     amount
     * @throws IllegalStateException if the stored amount carries more precision than the currency
     *     admits, or the currency is unknown
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
        String stored = row.getString(column);
        try {
            return Currency.valueOf(stored);
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

    /**
     * Rows that each read back, and together make an aggregate the domain refuses. No single column is
     * at fault, so the aggregate and the tables it spans are named instead.
     *
     * <p>The domain's refusal is deliberately not carried, as message or cause: it quotes what the owner
     * wrote, such as a category, and the aggregate's id is enough to find the rows.
     */
    static IllegalStateException unreconstructable(String aggregate, Object id, String tables) {
        return new IllegalStateException("Stored " + aggregate + " " + id + " in " + tables
                + " cannot be reconstructed: the domain refuses its rows together");
    }
}
