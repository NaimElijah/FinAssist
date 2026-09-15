/**
 * Budgeting feature - budgets, categories and categorisation rules.
 *
 * Mirrors the `budgeting` bounded context in the Java ledger.
 */
import type { RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { BudgetReviewPage } from './components/BudgetReviewPage';
import { BudgetsPage } from './components/BudgetsPage';
import { BUDGET_PATH, BUDGETS_PATH } from './components/paths';

/**
 * The budgeting area's routes, under the base path `/budgets`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`.
 *
 * `BUDGET_PATH`'s dynamic `:budgetId` is listed last: a budget's review page is titled "Budget
 * review" until its period is known, because a `handle` is static (see `RouteHandle`) and the
 * period is known only once the budgets list has loaded.
 */
export const budgetingRoutes: RouteObject[] = [
    {
        path: BUDGETS_PATH,
        Component: BudgetsPage,
        handle: { title: 'Budgets' } satisfies RouteHandle,
    },
    {
        path: BUDGET_PATH,
        Component: BudgetReviewPage,
        handle: { title: 'Budget review' } satisfies RouteHandle,
    },
];
