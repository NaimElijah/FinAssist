package io.github.naimelijah.finassist.ledger.accounting.domain.account;

/**
 * The five classic account types of double-entry bookkeeping, each with the sign its balance
 * normally carries.
 *
 * <p>The type is what lets a balance mean something. A positive balance on an {@link #ASSET} is
 * money held; a positive balance on a {@link #LIABILITY} is money owed. Without this, the sign of a
 * number is ambiguous and every report has to guess.
 *
 * <p>Income and expense are accounts, not labels. That is what makes an everyday purchase balance:
 * money leaves an asset and arrives at an expense, and the two entries sum to zero (ADR-011).
 */
public enum AccountType {
    /** What is owned: a current account, cash, an investment account. */
    ASSET(NormalBalance.DEBIT),
    /** What is owed: a credit card, a loan. */
    LIABILITY(NormalBalance.CREDIT),
    /** Where money comes from: salary, interest, a gift. */
    INCOME(NormalBalance.CREDIT),
    /** Where money goes: groceries, rent, a coffee. */
    EXPENSE(NormalBalance.DEBIT),
    /** The owner's stake, and the counterpart of an opening balance. */
    EQUITY(NormalBalance.CREDIT);

    private final NormalBalance normalBalance;

    AccountType(NormalBalance normalBalance) {
        this.normalBalance = normalBalance;
    }

    public NormalBalance normalBalance() {
        return normalBalance;
    }

    /** True when a positive balance on this type is the ordinary state of affairs. */
    public boolean increasesWithDebit() {
        return normalBalance == NormalBalance.DEBIT;
    }

    /** Which side of the books increases this type of account. */
    public enum NormalBalance {
        DEBIT,
        CREDIT
    }
}
