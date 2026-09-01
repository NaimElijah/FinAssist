package io.github.naimelijah.finassist.ledger.budgeting.application.port.out;

import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.List;

/**
 * Where budgeting asks what was spent.
 *
 * <p>{@code Budget.remaining} takes spend as an argument and stores none, which is the right shape for
 * a value object and leaves somebody having to supply it. This is that somebody, declared by the
 * context that needs the answer rather than offered by the one that has it — so budgeting depends on
 * an interface it owns, and the translation depends on budgeting rather than the other way round.
 *
 * <p>Nothing inside the ledger implements this. The translation over accounting lives in the
 * composition root, which is the only package permitted to see two contexts at once (ADR-022).
 */
public interface SpendJournal {

    /**
     * Everything spent during the period, in the given currency and no other.
     *
     * <p>The currency is a parameter rather than a filter the caller applies afterwards, because
     * FinAssist holds no exchange rates (ADR-012) and a budget covers exactly one currency. Asking for
     * the currency makes that the question rather than a silent omission from the answer: spend in
     * another currency belongs to another budget, exactly as {@code Balance.of} yields a balance per
     * account rather than refusing a book that holds two currencies.
     */
    List<SpendRecord> spendDuring(BudgetPeriod period, Currency currency);
}
