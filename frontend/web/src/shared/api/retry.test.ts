// @vitest-environment node
// No DOM: the predicate reads only the value it is handed.

import { describe, expect, it } from 'vitest';

import { ApiBodyError, ApiError } from './client';
import { isRetryable } from './retry';

const URL = '/ledger/api/v1/accounts';

/** An ApiError carrying a problem with `status`, as requestJson throws for a non-2xx response. */
function apiError(status: number): ApiError {
    return new ApiError(status, URL, `failed with ${String(status)}`, {
        status,
        code: 'UNDER_TEST',
    });
}

/**
 * Failures a second attempt would only repeat: an answer from the server, or a bug. A 503 is among
 * them: the assistant answers it as `PRICE_UNAVAILABLE`, a stale feed that no retry outlasts.
 */
const NOT_RETRYABLE: readonly { readonly name: string; readonly error: unknown }[] = [
    { name: 'a_400', error: apiError(400) },
    { name: 'a_404', error: apiError(404) },
    { name: 'a_409', error: apiError(409) },
    { name: 'a_422', error: apiError(422) },
    { name: 'a_503', error: apiError(503) },
    { name: 'a_2xx_without_its_JSON_body', error: new ApiBodyError(URL, 'Body was not JSON') },
    { name: 'a_plain_Error', error: new Error('mapping failed') },
    { name: 'a_thrown_string', error: 'a string was thrown' },
];

/** Failures a second attempt might not meet: the network, or the server failing. */
const RETRYABLE: readonly { readonly name: string; readonly error: unknown }[] = [
    { name: 'a_500', error: apiError(500) },
    { name: 'a_502', error: apiError(502) },
    { name: 'a_504', error: apiError(504) },
    {
        name: 'a_500_without_a_problem',
        error: new ApiError(500, URL, 'Request failed with 500', undefined),
    },
    { name: 'a_network_TypeError', error: new TypeError('Failed to fetch') },
];

describe('isRetryable', () => {
    for (const { name, error } of NOT_RETRYABLE) {
        it(`Given_${name}_When_classified_Then_it_is_not_retryable`, () => {
            expect(isRetryable(error)).toBe(false);
        });
    }

    for (const { name, error } of RETRYABLE) {
        it(`Given_${name}_When_classified_Then_it_is_retryable`, () => {
            expect(isRetryable(error)).toBe(true);
        });
    }
});
