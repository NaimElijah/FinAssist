/**
 * The portfolio area's route table.
 *
 * Its own file, apart from `index.ts`, the entry shape planning set: the entry stays a list of
 * re-exports, and the table that imports every page lives here.
 */
import type { RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { HoldingPage } from './components/HoldingPage';
import { HOLDING_PATH, PORTFOLIO_PATH } from './components/paths';
import { PortfolioPage } from './components/PortfolioPage';

/**
 * The portfolio area's routes, under the base path `/portfolio`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`.
 *
 * A holding's page is titled "Holding" by its route: a `handle` is static, so the page names its
 * own symbol through `useDocumentTitle` once mounted.
 */
export const portfolioRoutes: RouteObject[] = [
    {
        path: PORTFOLIO_PATH,
        Component: PortfolioPage,
        handle: { title: 'Portfolio' } satisfies RouteHandle,
    },
    {
        path: HOLDING_PATH,
        Component: HoldingPage,
        handle: { title: 'Holding' } satisfies RouteHandle,
    },
];
