package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * The two sealed types planning stores, encoded as a discriminator and the columns each variant
 * needs, and decoded back, with the conversions every planning row shares.
 *
 * <p>Kept in one place because the encoding and the decoding have to agree, and a mismatch between
 * them is the kind of defect that only shows up on a read months later. The {@code CHECK}
 * constraints in {@code V3_1__planning_schema.sql} say the same thing in SQL, so a combination
 * neither Java nor this class can produce is one the database will not hold either.
 *
 * <p>A stored value that cannot be read back is an {@link IllegalStateException} naming where it sits,
 * never the {@link IllegalArgumentException} the domain raises. The store holding a bad row is a server
 * fault; letting the domain's exception escape would have the problem handler answer it as a rule the
 * caller broke, with a 422 and no log line (ADR-028). Duplicated per context for the reason {@code
 * AccountingRows} gives.
 */
final class PlanningRows {

    /** The table both sealed types are stored in. */
    static final String PLANNED_FLOW = "planning.planned_flow";

    private PlanningRows() {}

    /** The stored shape of a {@link Recurrence}: which variant, and its parameters. */
    record StoredRecurrence(String kind, String cadence, Integer interval, LocalDate until, Integer occurrences) {

        static StoredRecurrence of(Recurrence recurrence) {
            return switch (recurrence) {
                case Recurrence.Once ignored -> new StoredRecurrence("ONCE", null, null, null, null);
                case Recurrence.Until until ->
                    new StoredRecurrence("UNTIL", until.cadence().name(), until.interval(), until.until(), null);
                case Recurrence.Times times ->
                    new StoredRecurrence("TIMES", times.cadence().name(), times.interval(), null, times.count());
            };
        }

        Recurrence toDomain() {
            return switch (kind) {
                case "ONCE" -> new Recurrence.Once();
                case "UNTIL" -> new Recurrence.Until(storedCadence(), interval, until);
                case "TIMES" -> new Recurrence.Times(storedCadence(), interval, occurrences);
                default -> throw unmappable(PLANNED_FLOW, "recurrence", kind);
            };
        }

        private Cadence storedCadence() {
            return enumerated(Cadence.class, PLANNED_FLOW, "cadence", cadence);
        }
    }

    /** The stored shape of a {@link PlanState}: which variant, when, and why where there is a why. */
    record StoredPlanState(String state, LocalDate on, String reason) {

        static StoredPlanState of(PlanState state) {
            return switch (state) {
                case PlanState.Planned ignored -> new StoredPlanState("PLANNED", null, null);
                case PlanState.Committed committed -> new StoredPlanState("COMMITTED", committed.committedOn(), null);
                case PlanState.Realised realised -> new StoredPlanState("REALISED", realised.realisedOn(), null);
                case PlanState.Cancelled cancelled ->
                    new StoredPlanState("CANCELLED", cancelled.cancelledOn(), cancelled.reason());
            };
        }

        PlanState toDomain() {
            return switch (state) {
                case "PLANNED" -> new PlanState.Planned();
                case "COMMITTED" -> new PlanState.Committed(on);
                case "REALISED" -> new PlanState.Realised(on);
                case "CANCELLED" -> new PlanState.Cancelled(on, reason);
                default -> throw unmappable(PLANNED_FLOW, "state", state);
            };
        }
    }

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
     */
    static IllegalStateException unreconstructable(String aggregate, Object id, String tables, RuntimeException cause) {
        return new IllegalStateException(
                "Stored " + aggregate + " " + id + " in " + tables + " cannot be reconstructed: " + cause.getMessage(),
                cause);
    }
}
