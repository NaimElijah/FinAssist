/**
 * The planning area's route table.
 *
 * Its own file, apart from `index.ts`: the index redirect needs JSX, and in a `.tsx` entry point
 * `react-refresh/only-export-components` reads the re-exported `AccountOptionsContext` as a
 * component and flags this table beside it. A plain `.ts` index re-exporting both is not scanned.
 */
import { Navigate, type RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { FLOWS_PATH, PLANNING_PATH } from './components/paths';
import { PlannedFlowsPage } from './components/PlannedFlowsPage';
import { PlanningLayout } from './components/PlanningLayout';
import { ProjectionPage } from './components/ProjectionPage';
import { ScenariosPage } from './components/ScenariosPage';

/**
 * The planning area's routes, under the base path `/planning`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`.
 *
 * The layout route carries the area's own title, "Planning"; each screen beneath it carries a
 * more specific one, which the app layout prefers as the deepest match. The index route redirects
 * to Flows, since a plan with nothing in it yet is still the area's primary screen.
 *
 * The projection screen reads its account choices through `AccountOptionsContext`, so src/app
 * must provide one around these routes.
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
            {
                path: 'scenarios',
                Component: ScenariosPage,
                handle: { title: 'Scenarios' } satisfies RouteHandle,
            },
            {
                path: 'projection',
                Component: ProjectionPage,
                handle: { title: 'Scenario projection' } satisfies RouteHandle,
            },
        ],
    },
];
