/**
 * Planning feature - planned flows, scenarios and their projections.
 *
 * Mirrors the `planning` bounded context in the Java ledger: what the owner intends, kept apart
 * from `accounting`, which records what happened, so a plan and a fact are never summed together
 * (ADR-015).
 */
import type { RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { PlanningPage } from './components/PlanningPage';

/**
 * The planning area's routes, under the base path `planning`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`. This is the route
 * contract; the page behind it is replaced as screens are built.
 */
export const planningRoutes: RouteObject[] = [
    {
        path: 'planning',
        Component: PlanningPage,
        handle: { title: 'Planning' } satisfies RouteHandle,
    },
];
