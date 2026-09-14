/**
 * Budgeting feature - budgets, categories and categorisation rules.
 *
 * Mirrors the `budgeting` bounded context in the Java ledger.
 */
import type { RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { BudgetsPage } from './components/BudgetsPage';

/**
 * The budgeting area's routes, under the base path `budgets`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`. This is the route
 * contract; the page behind it is replaced as screens are built.
 */
export const budgetingRoutes: RouteObject[] = [
    {
        path: 'budgets',
        Component: BudgetsPage,
        handle: { title: 'Budgets' } satisfies RouteHandle,
    },
];
