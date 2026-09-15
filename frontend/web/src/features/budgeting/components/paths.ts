/**
 * The budgeting area's URLs, written once for its routes and its links alike.
 */
import { generatePath } from 'react-router';

/** The budgets list and define-budget form. */
export const BUDGETS_PATH = '/budgets';

/**
 * Categorisation rules. A static route one segment below `/budgets`, the same depth as
 * `BUDGET_PATH`'s dynamic `:budgetId` - so, unlike accounting's two-segment
 * `RECORD_TRANSACTION_PATH`, the two routes genuinely compete for a match here. React Router
 * ranks a static path segment above a dynamic one regardless of declaration order, so
 * `/budgets/rules` always resolves to this route rather than being read as a budget id;
 * `CategorisationRulesPage.test.tsx` proves it.
 */
export const CATEGORISATION_RULES_PATH = '/budgets/rules';

/** One budget's review. */
export const BUDGET_PATH = '/budgets/:budgetId';

/**
 * The URL of one budget's review page.
 *
 * @param budgetId - the budget's id. `generatePath` encodes it as a single path segment.
 * @returns the path, such as `/budgets/0b7d2a4e-…`.
 */
export function budgetReviewPath(budgetId: string): string {
    return generatePath(BUDGET_PATH, { budgetId });
}
