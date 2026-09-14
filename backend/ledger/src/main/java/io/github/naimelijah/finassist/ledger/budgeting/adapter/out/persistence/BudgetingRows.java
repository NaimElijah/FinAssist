package io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.math.BigDecimal;
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
 */
final class BudgetingRows {

    private BudgetingRows() {}

    /**
     * Reads a monetary amount from an amount column and its currency column.
     *
     * @throws IllegalStateException if the stored amount carries more precision than the currency
     *     admits, or the currency is unknown
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
     */
    static IllegalStateException unreconstructable(String aggregate, Object id, String tables, RuntimeException cause) {
        return new IllegalStateException(
                "Stored " + aggregate + " " + id + " in " + tables + " cannot be reconstructed: " + cause.getMessage(),
                cause);
    }
}
