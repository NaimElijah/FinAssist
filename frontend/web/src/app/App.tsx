import { type QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, type RouterProviderProps } from 'react-router/dom';

import { SessionGate } from './session/SessionGate';

/** The router and query client the app runs on, each created once by the caller. */
export interface AppProps {
    readonly router: RouterProviderProps['router'];
    readonly queryClient: QueryClient;
}

/**
 * Application shell: the providers every screen needs, and the session gate around the router,
 * so nothing behind it - no route, no ledger or assistant call - runs before a session exists
 * (ADR-030).
 *
 * Both the router and the query client are passed in rather than created here. main.tsx gives it
 * a browser router and one client per page load; a test gives it a memory router and a fresh
 * client, over the same composition. The routes it renders come from routes.tsx, still the only
 * place that knows every feature at once. A feature never imports another feature, or src/app;
 * see `tests/architecture.test.ts`.
 *
 * @param props - see `AppProps`.
 */
export function App({ router, queryClient }: AppProps) {
    return (
        <QueryClientProvider client={queryClient}>
            <SessionGate>
                <RouterProvider router={router} />
            </SessionGate>
        </QueryClientProvider>
    );
}
