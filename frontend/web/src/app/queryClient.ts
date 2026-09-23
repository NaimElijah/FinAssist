/**
 * The TanStack Query client configuration the app runs with (ADR-029).
 */
import { hashKey, MutationCache, type Query, QueryCache, QueryClient } from '@tanstack/react-query';

import { ApiError } from '@/shared/api/client';
import { isRetryable } from '@/shared/api/retry';

import { sessionKeys } from './session/sessionQueries';

/** The status every one of the three services answers "no session" with (ADR-030). */
const UNAUTHENTICATED_STATUS = 401;

/**
 * At most one retry. It absorbs a dropped connection or a backend restarting under Compose,
 * without hiding a real outage behind several attempts, each waiting out its own backoff.
 */
const MAX_QUERY_RETRIES = 1;

/**
 * Refetching on window focus suits data other people change. Here the owner is the only writer,
 * and every write invalidates what it affects, so a refetch on focus would only add requests.
 */
const REFETCH_ON_WINDOW_FOCUS = false;

/**
 * A write is never retried, whatever failed. A retried POST can create twice, and the journal is
 * append-only, so the only remedy for a duplicated transaction is to void it (ADR-028).
 */
const MUTATION_RETRIES = 0;

/**
 * Whether a failed query is tried again: at most once, and only when `isRetryable` holds - a
 * network failure, or a 5xx other than a 503.
 *
 * @param failureCount - retries already made. TanStack Query passes 0 on the first failure.
 * @param error - what the query function threw.
 * @returns whether to retry.
 */
function shouldRetryQuery(failureCount: number, error: unknown): boolean {
    return failureCount < MAX_QUERY_RETRIES && isRetryable(error);
}

/** Whether `query` is the session check itself, by exact key rather than by prefix. */
function isSessionQuery(query: Pick<Query, 'queryKey'>): boolean {
    return hashKey(query.queryKey) === hashKey(sessionKeys.me);
}

/**
 * Recheck the session once a *different* query or mutation answers 401, so a session the
 * gateway ended mid-visit - by a restart, or by its in-memory session expiring - is noticed the
 * next time anything asks, rather than only the next time the owner happens to reload.
 *
 * The session query's own 401 is excluded deliberately: invalidating it there would refetch it,
 * which would 401 again, which would invalidate it again - an infinite loop for exactly the
 * ordinary signed-out state `SessionGate` already renders `SignIn` for.
 *
 * @param queryClient - invalidated once a qualifying 401 is seen. Read through a holder rather
 *   than closed over directly, since the client this function is attached to does not exist yet
 *   at the point `QueryCache`/`MutationCache` are constructed.
 * @param error - whatever the query or mutation threw.
 * @param query - the failed query, or `undefined` for a mutation, which has no query key of its
 *   own to be the session query.
 */
function recheckSessionOn401(
    queryClient: { current?: QueryClient },
    error: unknown,
    query?: Pick<Query, 'queryKey'>,
): void {
    const isUnauthenticated = error instanceof ApiError && error.status === UNAUTHENTICATED_STATUS;
    if (isUnauthenticated && !(query !== undefined && isSessionQuery(query))) {
        void queryClient.current?.invalidateQueries({ queryKey: sessionKeys.me });
    }
}

/**
 * Create the app's query client, with an empty cache.
 *
 * A new client on every call: main.tsx creates one for the page, and each test creates its own,
 * so no test inherits another's cache.
 *
 * @returns a client whose queries retry once after a network failure or a 5xx other than a 503
 *   and never after anything else, never refetch on window focus, whose mutations never retry,
 *   and which rechecks the session once any other query or mutation answers 401 (ADR-030).
 */
export function createQueryClient(): QueryClient {
    // Assigned right after construction, below - see recheckSessionOn401's own doc comment for
    // why this cannot simply be a variable captured before the client that will fill it exists.
    const holder: { current?: QueryClient } = {};

    const queryClient = new QueryClient({
        queryCache: new QueryCache({
            onError: (error, query) => {
                recheckSessionOn401(holder, error, query);
            },
        }),
        mutationCache: new MutationCache({
            onError: (error) => {
                recheckSessionOn401(holder, error);
            },
        }),
        defaultOptions: {
            queries: { retry: shouldRetryQuery, refetchOnWindowFocus: REFETCH_ON_WINDOW_FOCUS },
            mutations: { retry: MUTATION_RETRIES },
        },
    });
    holder.current = queryClient;
    return queryClient;
}
