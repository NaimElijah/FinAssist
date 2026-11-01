package io.github.naimelijah.finassist.ledger.accounting.domain.account;

/**
 * The five classic account types of double-entry bookkeeping, each with the sign its balance
 * normally carries.
 *
 * <p>The type is what lets a balance mean something. Entries are signed, a debit positive and a
 * credit negative, so a positive balance on an {@link #ASSET} is money held, while money owed on a
 * {@link #LIABILITY} is a negative balance. Without the type, the sign of a number is ambiguous and
 * every report has to guess.
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

    /** The side of the books whose entries increase this type of account. */
    public NormalBalance normalBalance() {
        return normalBalance;
    }

    /**
     * True when a debit, a positive entry, increases this type of account; false when a credit, a
     * negative entry, does. For the credit types the ordinary balance is therefore negative.
     */
    public boolean increasesWithDebit() {
        return normalBalance == NormalBalance.DEBIT;
    }

    /** Which side of the books increases this type of account. */
    public enum NormalBalance {
        /** Positive entries increase the account. */
        DEBIT,
        /** Negative entries increase the account. */
        CREDIT
    }
}
