/**
 * A categorisation rule as the budgeting screens hold it: a fragment to match a transaction's
 * description on, and the category it assigns.
 */

/**
 * One rule. Rules are tried in the order they were added and the first match wins
 * (requirements § Tracking), which is why they are always held as an ordered list, never sorted.
 */
export interface CategorisationRule {
    readonly fragment: string;
    readonly category: string;
}
