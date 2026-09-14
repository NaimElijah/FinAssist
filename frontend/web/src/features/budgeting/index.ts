/**
 * Budgeting feature - budgets, categories and categorisation rules.
 *
 * Mirrors the `budgeting` bounded context in the Java ledger.
 */
import type { RouteObject } from 'react-router';

import { BudgetsPage } from './components/BudgetsPage';

/**
 * The budgeting area's routes, under the base path `budgets`. src/app mounts them inside the
 * shared layout. This is the route contract; the page behind it is replaced as screens are built.
 */
export const budgetingRoutes: RouteObject[] = [{ path: 'budgets', Component: BudgetsPage }];
