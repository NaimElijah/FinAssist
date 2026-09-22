/**
 * Render a feature's routes the way the app runs them, for a component test.
 *
 * Generic on purpose: it takes the routes to mount and imports no feature, so every feature's
 * tests share it and `tests/architecture.test.ts` still holds. A feature test cannot mount the
 * real table in `src/app/routes`, because no feature may import `src/app`.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';

import { type WriteAccess, WriteAccessContext } from '@/shared/lib/writeAccess';

/** What a test can reach after rendering: the DOM queries, and the router and cache behind them. */
export type RenderedRoutes = RenderResult & {
    readonly router: ReturnType<typeof createMemoryRouter>;
    readonly queryClient: QueryClient;
};

/** `renderRoutes`'s own options, every one of them optional. */
export interface RenderRoutesOptions {
    /**
     * The write access the mounted screen sees. Defaults to an owner's (`{canWrite: true}`), so
     * every screen test written before Task 16 keeps rendering with write access and needs no
     * change; a test of a read-only session opts in deliberately.
     */
    readonly access?: WriteAccess;
}

/**
 * Mount `routes` under a pathless-layout root at `/`, in an in-memory router starting at `path`,
 * inside a fresh query client.
 *
 * The client retries nothing. The app's own client retries a network failure or a 5xx other than a
 * 503 once, after a backoff, which would make a test of a failure wait out that backoff; the retry
 * policy itself is tested in `src/app/queryClient.test.ts`. A new client per call means no test
 * inherits another's cache.
 *
 * @param routes - the routes to mount, with paths relative to `/`, as a feature exports them.
 * @param path - the location the router starts at, such as `/accounts`.
 * @param options - see `RenderRoutesOptions`.
 * @returns Testing Library's render result, plus the router and the query client.
 */
export function renderRoutes(
    routes: RouteObject[],
    path: string,
    options?: RenderRoutesOptions,
): RenderedRoutes {
    const access = options?.access ?? { canWrite: true };
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    // A route with no component renders its matched child, so this root stands in for the
    // app's layout without bringing its navigation, title or focus handling into a feature test.
    const router = createMemoryRouter([{ path: '/', children: routes }], {
        initialEntries: [path],
    });
    const result = render(
        <QueryClientProvider client={queryClient}>
            <WriteAccessContext value={access}>
                <RouterProvider router={router} />
            </WriteAccessContext>
        </QueryClientProvider>,
    );
    return { ...result, router, queryClient };
}
