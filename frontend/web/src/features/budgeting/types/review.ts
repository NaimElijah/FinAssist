/**
 * How a budget is doing, as the review screen shows it: every budgeted category, and the two
 * kinds of spend that fell outside it (requirements § Tracking).
 */
import type { Currency, Money } from './money';
import type { BudgetPeriod } from './period';

/** One budgeted category's limit, spend and what remains, derived by the ledger. */
export interface CategorySpend {
    readonly category: string;
    readonly limit: Money;
    readonly spent: Money;
    /** May be negative: the ledger's string as received, never recomputed here (ADR-029). */
    readonly remaining: Money;
    readonly overspent: boolean;
}

/** Spend that matched a rule for a category this budget sets no limit for. */
export interface UnbudgetedSpend {
    readonly category: string;
    readonly spent: Money;
}

/**
 * A budget's review. `unbudgeted` is ordered - the ledger sends it as an array rather than an
 * object precisely so that order survives the wire (ADR-028) - and is kept as one here too.
 */
export interface BudgetReview {
    /** The reviewed budget's id. */
    readonly budget: string;
    readonly period: BudgetPeriod;
    readonly currency: Currency;
    readonly categories: readonly CategorySpend[];
    readonly unbudgeted: readonly UnbudgetedSpend[];
    /** Spend that matched no rule at all - the figure that says the rules need work. */
    readonly uncategorised: Money;
}
