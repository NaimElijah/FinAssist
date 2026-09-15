/**
 * The TanStack Query client configuration the app runs with (ADR-029).
 */
import { QueryClient } from '@tanstack/react-query';

import { isRetryable } from '@/shared/api/retry';

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

/**
 * Create the app's query client, with an empty cache.
 *
 * A new client on every call: main.tsx creates one for the page, and each test creates its own,
 * so no test inherits another's cache.
 *
 * @returns a client whose queries retry once after a network failure or a 5xx other than a 503
 *   and never after anything else, never refetch on window focus, and whose mutations never retry.
 */
export function createQueryClient(): QueryClient {
    return new QueryClient({
        defaultOptions: {
            queries: { retry: shouldRetryQuery, refetchOnWindowFocus: REFETCH_ON_WINDOW_FOCUS },
            mutations: { retry: MUTATION_RETRIES },
        },
    });
}
