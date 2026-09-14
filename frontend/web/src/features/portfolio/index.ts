/**
 * Portfolio feature - holdings, positions and performance.
 *
 * Mirrors the `portfolio` bounded context in the Python assistant.
 */
import type { RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { PortfolioPage } from './components/PortfolioPage';

/**
 * The portfolio area's routes, under the base path `portfolio`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`. This is the route
 * contract; the page behind it is replaced as screens are built.
 */
export const portfolioRoutes: RouteObject[] = [
    {
        path: 'portfolio',
        Component: PortfolioPage,
        handle: { title: 'Portfolio' } satisfies RouteHandle,
    },
];
