// @vitest-environment node
// No DOM: a QueryClient and a MutationObserver are plain objects, and nothing here renders.

import { MutationObserver } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';

import { createQueryClient } from './queryClient';
import { sessionKeys } from './session/sessionQueries';

const URL = '/ledger/api/v1/accounts';

/** An ApiError carrying a problem with `status`, as requestJson throws for a non-2xx response. */
function apiError(status: number): ApiError {
    return new ApiError(status, URL, `failed with ${String(status)}`, {
        status,
        code: 'UNDER_TEST',
    });
}

/**
 * How many times the app's query client calls a query function that always fails with `error`.
 *
 * `retryDelay` is zeroed here and only here: the default backoff waits a second before a retry,
 * and what is under test is how many attempts are made, not how long they are spaced.
 */
async function attemptsBeforeGivingUp(error: unknown): Promise<number> {
    const queryFn = vi.fn<() => Promise<never>>().mockRejectedValue(error);

    await expect(
        createQueryClient().query({ queryKey: ['under-test'], queryFn, retryDelay: 0 }),
    ).rejects.toBe(error);

    return queryFn.mock.calls.length;
}

/**
 * Failures a second attempt would only repeat: an answer from the server, or a bug. A 503 is among
 * them: the assistant answers it as `PRICE_UNAVAILABLE`, a stale feed that no retry's backoff
 * outlasts.
 */
const NOT_RETRIED: readonly { readonly name: string; readonly error: unknown }[] = [
    { name: 'a_400', error: apiError(400) },
    { name: 'a_404', error: apiError(404) },
    { name: 'a_409', error: apiError(409) },
    { name: 'a_503', error: apiError(503) },
    { name: 'a_2xx_without_its_JSON_body', error: new ApiBodyError(URL, 'Body was not JSON') },
    { name: 'a_plain_Error', error: new Error('mapping failed') },
];

/** Failures a second attempt might not meet: the network, or the server failing. */
const RETRIED_ONCE: readonly { readonly name: string; readonly error: unknown }[] = [
    { name: 'a_500', error: apiError(500) },
    { name: 'a_502', error: apiError(502) },
    { name: 'a_network_TypeError', error: new TypeError('Failed to fetch') },
];

describe('createQueryClient', () => {
    for (const { name, error } of NOT_RETRIED) {
        it(`Given_a_query_failing_with_${name}_When_fetched_Then_it_is_attempted_once`, async () => {
            expect(await attemptsBeforeGivingUp(error)).toBe(1);
        });
    }

    for (const { name, error } of RETRIED_ONCE) {
        it(`Given_a_query_failing_with_${name}_When_fetched_Then_it_is_retried_exactly_once`, async () => {
            expect(await attemptsBeforeGivingUp(error)).toBe(2);
        });
    }

    it('Given_the_app_query_client_When_its_query_defaults_are_read_Then_queries_do_not_refetch_on_focus', () => {
        expect(createQueryClient().getDefaultOptions().queries?.refetchOnWindowFocus).toBe(false);
    });

    it('Given_the_app_query_client_When_its_mutation_defaults_are_read_Then_mutations_never_retry', () => {
        expect(createQueryClient().getDefaultOptions().mutations?.retry).toBe(0);
    });

    it('Given_a_mutation_failing_with_a_503_When_run_through_the_app_query_client_Then_it_is_attempted_exactly_once', async () => {
        // ADR-028: a retried POST could double-create, and the journal is append-only, so even a
        // failure a query would retry is never re-sent for a write.
        const failure = apiError(503);
        const mutationFn = vi.fn<() => Promise<never>>().mockRejectedValue(failure);
        const observer = new MutationObserver(createQueryClient(), { mutationFn });

        await expect(observer.mutate()).rejects.toBe(failure);
        expect(mutationFn).toHaveBeenCalledTimes(1);
    });

    it('Given_two_calls_When_a_client_is_created_each_time_Then_they_share_no_cache', () => {
        // A fresh client per call is what lets each test render the app with an empty cache.
        expect(createQueryClient()).not.toBe(createQueryClient());
    });

    it('Given_a_401_from_a_query_other_than_the_session_check_When_it_fails_Then_the_session_is_invalidated_once', async () => {
        const queryClient = createQueryClient();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const queryFn = vi.fn<() => Promise<never>>().mockRejectedValue(apiError(401));

        await expect(
            queryClient.query({ queryKey: ['under-test'], queryFn, retryDelay: 0 }),
        ).rejects.toBeInstanceOf(ApiError);

        await vi.waitFor(() => {
            expect(invalidateSpy).toHaveBeenCalledExactlyOnceWith({ queryKey: sessionKeys.me });
        });
    });

    it('Given_the_session_checks_own_401_When_it_fails_Then_the_session_is_not_invalidated_again', async () => {
        // Invalidating here would refetch the session, which would 401 again, which would
        // invalidate again - forever. This is the one case the recheck above must exclude.
        const queryClient = createQueryClient();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const queryFn = vi.fn<() => Promise<never>>().mockRejectedValue(apiError(401));

        await expect(
            queryClient.query({ queryKey: sessionKeys.me, queryFn, retryDelay: 0 }),
        ).rejects.toBeInstanceOf(ApiError);

        // There is no success condition to await for "nothing happens", so this gives any
        // queued microtask a turn before asserting the negative.
        await new Promise((resolve) => setTimeout(resolve, 0));
        expect(invalidateSpy).not.toHaveBeenCalled();
    });

    it('Given_a_401_from_a_failed_mutation_When_it_fails_Then_the_session_is_invalidated_once', async () => {
        const queryClient = createQueryClient();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const failure = apiError(401);
        const mutationFn = vi.fn<() => Promise<never>>().mockRejectedValue(failure);
        const observer = new MutationObserver(queryClient, { mutationFn });

        await expect(observer.mutate()).rejects.toBe(failure);

        await vi.waitFor(() => {
            expect(invalidateSpy).toHaveBeenCalledExactlyOnceWith({ queryKey: sessionKeys.me });
        });
    });
});
