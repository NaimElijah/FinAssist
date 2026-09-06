package io.github.naimelijah.finassist.ledger.budgeting.adapter.out.persistence;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.CategorisationRuleRepository;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.Category;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Categorisation rules, in PostgreSQL.
 *
 * <p>The {@code ORDER BY ordinal} is the whole reason the table has an identity column. The port
 * makes insertion order part of its contract: a review folds the rules over each spend record and
 * takes the first match, so the order decides the answer. A query without it would return rows in
 * whatever order the heap happened to hold them, which is the same defect as a hash-ordered set and
 * harder to notice.
 */
public class JdbcCategorisationRuleRepository implements CategorisationRuleRepository {

    private static final String SELECT_ALL = """
            SELECT fragment, category FROM budgeting.categorisation_rule ORDER BY ordinal
            """;

    private static final String INSERT = """
            INSERT INTO budgeting.categorisation_rule (fragment, category) VALUES (:fragment, :category)
            """;

    private final JdbcClient jdbc;

    public JdbcCategorisationRuleRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<CategorisationRule> all() {
        return jdbc.sql(SELECT_ALL)
                .query((row, rowNumber) ->
                        new CategorisationRule(row.getString("fragment"), Category.of(row.getString("category"))))
                .list();
    }

    @Override
    public void add(CategorisationRule rule) {
        Objects.requireNonNull(rule, "rule");
        jdbc.sql(INSERT)
                .param("fragment", rule.fragment())
                .param("category", rule.category().name())
                .update();
    }
}
