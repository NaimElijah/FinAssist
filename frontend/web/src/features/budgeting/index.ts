/**
 * Budgeting feature - budgets, categories and categorisation rules.
 *
 * Mirrors the `budgeting` bounded context in the Java ledger.
 */
import type { RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { BudgetsPage } from './components/BudgetsPage';
import { BUDGETS_PATH } from './components/paths';

/**
 * The budgeting area's routes, under the base path `/budgets`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`.
 */
export const budgetingRoutes: RouteObject[] = [
    {
        path: BUDGETS_PATH,
        Component: BudgetsPage,
        handle: { title: 'Budgets' } satisfies RouteHandle,
    },
];
