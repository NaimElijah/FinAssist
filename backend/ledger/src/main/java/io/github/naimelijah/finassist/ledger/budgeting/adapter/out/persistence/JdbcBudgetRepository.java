package io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.BudgetRepository;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.Budget;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Budgets, in PostgreSQL.
 *
 * <p>Ordinary mutable storage, unlike the journal. ADR-020's append-only obligation binds the record
 * of what happened, and a budget is not one - it is a limit the owner sets and may reset. ADR-015
 * draws that line between the contexts, and this is where it shows up in SQL. The port still exposes
 * no delete.
 */
public class JdbcBudgetRepository implements BudgetRepository {

    private static final String BUDGET_TABLE = "budgeting.budget";
    private static final String LIMIT_TABLE = "budgeting.budget_limit";

    private static final String UPSERT_BUDGET = """
            INSERT INTO budgeting.budget (id, period_start, period_end, currency)
            VALUES (:id, :periodStart, :periodEnd, :currency)
            ON CONFLICT (id) DO UPDATE
               SET period_start = EXCLUDED.period_start,
                   period_end   = EXCLUDED.period_end,
                   currency     = EXCLUDED.currency
            """;

    private static final String DELETE_LIMITS = """
            DELETE FROM budgeting.budget_limit WHERE budget_id = :budgetId
            """;

    private static final String INSERT_LIMIT = """
            INSERT INTO budgeting.budget_limit (budget_id, category, amount, currency)
            VALUES (:budgetId, :category, :amount, :currency)
            """;

    private static final String SELECT_BUDGET = """
            SELECT id, period_start, period_end, currency FROM budgeting.budget WHERE id = :id
            """;

    /** {@code BudgetRepository.all}'s contract: the most recent period leads, id breaking a tie. */
    private static final String SELECT_ALL_BUDGETS = """
            SELECT id, period_start, period_end, currency
              FROM budgeting.budget
             ORDER BY period_start DESC, id
            """;

    /**
     * Ordered by category so the map this builds is stable. {@code Budget} holds a {@code
     * Map.copyOf}, whose iteration order is unspecified, and both {@code BudgetReviewService} and
     * {@code BudgetReview} sort by category name to compensate; there is no reason for the store to
     * hand them a third order.
     */
    private static final String SELECT_LIMITS = """
            SELECT category, amount, currency
              FROM budgeting.budget_limit
             WHERE budget_id = :budgetId
             ORDER BY category
            """;

    private final JdbcClient jdbc;

    public JdbcBudgetRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Budget> findById(BudgetId id) {
        Objects.requireNonNull(id, "id");
        return jdbc.sql(SELECT_BUDGET)
                .param("id", id.value())
                .query(this::toBudget)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads each budget's limits with {@link #limitsOf}, the same helper {@link #findById} uses —
     * one further query per row rather than a join. One owner keeps few budgets, and ADR-028 accepts
     * that cost until a list grows past about a thousand rows, at which point it asks for pagination
     * instead.
     */
    @Override
    public List<Budget> all() {
        return jdbc.sql(SELECT_ALL_BUDGETS).query(this::toBudget).list();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Transactional because replacing the limits is a delete and several inserts, and a budget
     * left holding half of them would be a different budget rather than a failed write.
     */
    @Override
    @Transactional
    public void save(Budget budget) {
        Objects.requireNonNull(budget, "budget");
        jdbc.sql(UPSERT_BUDGET)
                .param("id", budget.id().value())
                .param("periodStart", budget.period().start())
                .param("periodEnd", budget.period().end())
                .param("currency", budget.currency().name())
                .update();

        jdbc.sql(DELETE_LIMITS).param("budgetId", budget.id().value()).update();

        for (Map.Entry<Category, Money> limit : budget.limits().entrySet()) {
            jdbc.sql(INSERT_LIMIT)
                    .param("budgetId", budget.id().value())
                    .param("category", limit.getKey().name())
                    .param("amount", limit.getValue().amount())
                    .param("currency", limit.getValue().currency().name())
                    .update();
        }
    }

    private Map<Category, Money> limitsOf(BudgetId id) {
        List<Map.Entry<Category, Money>> rows = jdbc.sql(SELECT_LIMITS)
                .param("budgetId", id.value())
                // The key names the budget and not the category, which is the owner's own word.
                .query((row, rowNumber) -> Map.entry(
                        category(row.getString("category")),
                        BudgetingRows.money(row, LIMIT_TABLE, "budget_id=" + id.value(), "amount", "currency")))
                .list();

        Map<Category, Money> limits = new LinkedHashMap<>();
        rows.forEach(row -> limits.put(row.getKey(), row.getValue()));
        return limits;
    }

    private static Category category(String stored) {
        try {
            return Category.of(stored);
        } catch (IllegalArgumentException refused) {
            throw BudgetingRows.unmappable(LIMIT_TABLE, "category", stored, refused);
        }
    }

    /**
     * Shared by {@link #findById} and {@link #all}, so the two never map a row two different ways.
     *
     * <p>Each column reads back on its own, and {@code budget_limit_in_budget_currency} ties every limit's
     * currency to its budget's (V2_2). No constraint holds yet that a budget has at least one limit
     * (#401). {@link Budget} refuses a budget with none, and a stored one it refuses is a server fault
     * naming the budget rather than a rule a caller broke.
     */
    private Budget toBudget(ResultSet row, int rowNumber) throws SQLException {
        BudgetId id = new BudgetId(row.getObject("id", UUID.class));
        Currency currency = BudgetingRows.currency(row, BUDGET_TABLE, "currency");
        Map<Category, Money> limits = limitsOf(id);
        try {
            BudgetPeriod period = new BudgetPeriod(
                    row.getObject("period_start", LocalDate.class), row.getObject("period_end", LocalDate.class));
            return new Budget(id, period, currency, limits);
        } catch (IllegalArgumentException refused) {
            throw BudgetingRows.unreconstructable("budget", id.value(), BUDGET_TABLE + " and " + LIMIT_TABLE);
        }
    }
}
