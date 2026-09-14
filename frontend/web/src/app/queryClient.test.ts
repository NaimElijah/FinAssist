// @vitest-environment node
// No DOM: a QueryClient and a MutationObserver are plain objects, and nothing here renders.

import { MutationObserver } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';

import { createQueryClient } from './queryClient';

describe('createQueryClient', () => {
    it('Given_the_app_query_client_When_its_query_defaults_are_read_Then_queries_retry_once_and_do_not_refetch_on_focus', () => {
        const { queries } = createQueryClient().getDefaultOptions();

        expect(queries?.retry).toBe(1);
        expect(queries?.refetchOnWindowFocus).toBe(false);
    });

    it('Given_the_app_query_client_When_its_mutation_defaults_are_read_Then_mutations_never_retry', () => {
        expect(createQueryClient().getDefaultOptions().mutations?.retry).toBe(0);
    });

    it('Given_a_mutation_that_fails_When_run_through_the_app_query_client_Then_it_is_attempted_exactly_once', async () => {
        // ADR-028: a retried POST could double-create, and the journal is append-only, so a
        // failed write is never re-sent on the client's own initiative.
        const mutationFn = vi.fn<() => Promise<never>>().mockRejectedValue(new Error('503'));
        const observer = new MutationObserver(createQueryClient(), { mutationFn });

        await expect(observer.mutate()).rejects.toThrow('503');
        expect(mutationFn).toHaveBeenCalledTimes(1);
    });

    it('Given_two_calls_When_a_client_is_created_each_time_Then_they_share_no_cache', () => {
        // A fresh client per call is what lets each test render the app with an empty cache.
        expect(createQueryClient()).not.toBe(createQueryClient());
    });
});
