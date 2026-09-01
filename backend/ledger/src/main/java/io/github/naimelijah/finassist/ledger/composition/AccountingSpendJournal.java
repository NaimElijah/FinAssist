package io.github.naimelijah.finassist.ledger.composition;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.JournalLine;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewJournalUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendJournal;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.out.SpendRecord;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetPeriod;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Gives budgeting its spend, by asking accounting.
 *
 * <p>This class is the reason the composition root exists. Budgeting declares {@link SpendJournal} in
 * its own vocabulary and may name no accounting type at any layer, so the translation can live in
 * neither context — {@code contexts_do_not_depend_on_each_other} fails for a budgeting adapter
 * importing accounting exactly as it would for a budgeting domain class. See ADR-022.
 *
 * <p>It goes through accounting's <strong>in-port</strong> rather than its repositories. That is the
 * in-process equivalent of the Published Language, and it is what keeps extracting either context a
 * change of transport rather than a change of design.
 *
 * <p>Two filters, and both are deliberate. Only {@code EXPENSE} lines are spend — an entry against the
 * cash account is the other side of the same purchase, and counting both would double every figure.
 * And only lines in the currency asked for, because FinAssist holds no rates (ADR-012); spend in
 * another currency belongs to a budget in that currency.
 */
public class AccountingSpendJournal implements SpendJournal {

    private final ViewJournalUseCase journal;

    public AccountingSpendJournal(ViewJournalUseCase journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    @Override
    public List<SpendRecord> spendDuring(BudgetPeriod period, Currency currency) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(currency, "currency");

        // postedBetween already drops voided transactions, so nothing here has to know to.
        return journal.postedBetween(period.start(), period.end()).stream()
                .filter(line -> line.accountType() == AccountType.EXPENSE)
                .filter(line -> line.amount().currency() == currency)
                .map(AccountingSpendJournal::toSpend)
                .toList();
    }

    private static SpendRecord toSpend(JournalLine line) {
        return new SpendRecord(line.description(), line.amount(), line.on());
    }
}
