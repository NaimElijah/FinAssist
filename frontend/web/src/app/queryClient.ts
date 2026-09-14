/**
 * The TanStack Query client configuration the app runs with (ADR-029).
 */
import { QueryClient } from '@tanstack/react-query';

import { ApiError } from '@/shared/api/client';

/**
 * At most one retry. It absorbs a dropped connection or a backend restarting under Compose,
 * without hiding a real outage behind several attempts, each waiting out its own backoff.
 */
const MAX_QUERY_RETRIES = 1;

/** The lowest status meaning the server failed, rather than that it refused the request. */
const FIRST_SERVER_ERROR_STATUS = 500;

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
 * Whether a failed query is tried again: at most once, and only when a second attempt could meet
 * a different outcome.
 *
 * That means a network failure, which `fetch` reports as a `TypeError` and `requestJson` rethrows,
 * or a 5xx `ApiError`, which a restarting backend answers. A 4xx is the server's considered
 * answer - a 404 stays missing and a 409 stays in conflict - so a retry would only delay showing
 * it. Anything else, such as an `ApiBodyError`, is a defect a retry would repeat. A `TypeError`
 * thrown by a defect in a query function is retried once as well; from here the two cannot be
 * told apart.
 *
 * @param failureCount - retries already made. TanStack Query passes 0 on the first failure.
 * @param error - what the query function threw.
 * @returns whether to retry.
 */
function shouldRetryQuery(failureCount: number, error: unknown): boolean {
    if (failureCount >= MAX_QUERY_RETRIES) {
        return false;
    }
    if (error instanceof ApiError) {
        return error.status >= FIRST_SERVER_ERROR_STATUS;
    }
    return error instanceof TypeError;
}

/**
 * Create the app's query client, with an empty cache.
 *
 * A new client on every call: main.tsx creates one for the page, and each test creates its own,
 * so no test inherits another's cache.
 *
 * @returns a client whose queries retry once after a network failure or a 5xx and never after
 *   anything else, never refetch on window focus, and whose mutations never retry.
 */
export function createQueryClient(): QueryClient {
    return new QueryClient({
        defaultOptions: {
            queries: { retry: shouldRetryQuery, refetchOnWindowFocus: REFETCH_ON_WINDOW_FOCUS },
            mutations: { retry: MUTATION_RETRIES },
        },
    });
}
