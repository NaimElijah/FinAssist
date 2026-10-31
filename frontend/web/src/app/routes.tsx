import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';

import { accountingRoutes } from '@/features/accounting';
import { budgetingRoutes } from '@/features/budgeting';
import { planningRoutes } from '@/features/planning';
import { portfolioRoutes } from '@/features/portfolio';
import type { RouteHandle } from '@/shared/lib/routeHandle';

import { Layout } from './Layout';
import { NotFound } from './NotFound';
import { PlanningAccountsBridge } from './PlanningAccountsBridge';
import { RootRouteError, RouteError } from './RouteError';

/** Where the root path sends the owner: the books come first. */
const LANDING_PATH = '/accounts';

/**
 * Wrap one feature's routes in a pathless route carrying the area's error boundary, so a failure
 * inside one area replaces that area alone and leaves the layout around it working.
 */
function area(children: RouteObject[]): RouteObject {
    return { ErrorBoundary: RouteError, children };
}

/**
 * The route table: every area under the shared layout, the root path redirecting to Accounts, and
 * a catch-all not-found page.
 *
 * Planning's routes render beneath `PlanningAccountsBridge`, a pathless layout route providing the
 * account choices planning declares, adapted from accounting's accounts.
 *
 * Exported so tests mount the real table in a memory router. The layout route carries a boundary
 * too, so a failure in the layout or the not-found page is logged and shown rather than falling
 * through to React Router's built-in error screen; `RootRouteError`, since the layout that would
 * give the failure its `<main>` and document title is the thing that failed.
 */
export const routes: RouteObject[] = [
    {
        path: '/',
        Component: Layout,
        ErrorBoundary: RootRouteError,
        children: [
            { index: true, element: <Navigate to={LANDING_PATH} replace /> },
            area(accountingRoutes),
            area(budgetingRoutes),
            area([{ Component: PlanningAccountsBridge, children: planningRoutes }]),
            area(portfolioRoutes),
            {
                path: '*',
                Component: NotFound,
                handle: { title: 'Page not found' } satisfies RouteHandle,
            },
        ],
    },
];

/**
 * Create the browser router main.tsx renders.
 *
 * Call it once per page load, outside React: RouterProvider expects one router instance for the
 * life of the page, not one per render.
 *
 * @returns a data router over `routes`, reading and writing the browser's URL.
 */
export function createAppRouter() {
    return createBrowserRouter(routes);
}
