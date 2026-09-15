/**
 * Planning feature - planned flows, scenarios and their projections.
 *
 * Mirrors the `planning` bounded context in the Java ledger: what the owner intends, kept apart
 * from `accounting`, which records what happened, so a plan and a fact are never summed together
 * (ADR-015).
 */
import { Navigate, type RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { FLOWS_PATH, PLANNING_PATH } from './components/paths';
import { PlannedFlowsPage } from './components/PlannedFlowsPage';
import { PlanningLayout } from './components/PlanningLayout';

/**
 * The planning area's routes, under the base path `/planning`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`.
 *
 * The layout route carries the area's own title, "Planning"; each screen beneath it carries a
 * more specific one, which the app layout prefers as the deepest match. The index route redirects
 * to Flows, since a plan with nothing in it yet is still the area's primary screen.
 */
export const planningRoutes: RouteObject[] = [
    {
        path: PLANNING_PATH,
        Component: PlanningLayout,
        handle: { title: 'Planning' } satisfies RouteHandle,
        children: [
            { index: true, element: <Navigate to={FLOWS_PATH} replace /> },
            {
                path: 'flows',
                Component: PlannedFlowsPage,
                handle: { title: 'Planned flows' } satisfies RouteHandle,
            },
        ],
    },
];
