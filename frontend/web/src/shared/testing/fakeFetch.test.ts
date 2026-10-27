// @vitest-environment node
// fetch, Headers and Response are Node globals, and this file exercises fakeFetch itself
// rather than a browser-only caller, so no DOM is needed here either.

import { describe, expect, it } from 'vitest';

import {
    assertNoUnplannedRequests,
    fakeFetch,
    jsonResponse,
    type RecordedRequest,
    unexpectedRequest,
} from './fakeFetch';

/**
 * The double's own guarantee: a mistake in a test fails it, even when the app swallows the error.
 *
 * `it.fails` passes only when the test fails, and Vitest counts an error thrown by an
 * `onTestFinished` hook - where fakeFetch reports - as the test failing. Each body below catches
 * whatever the request does, so the hook is the only thing left that can fail it.
 */
describe('fakeFetch failing a mistaken test', () => {
    it.fails('Given_an_unplanned_request_When_the_test_finishes_Then_it_fails', async () => {
        fakeFetch(unexpectedRequest);

        await fetch('https://ledger.test/api/v1/accounts').catch(() => undefined);
    });

    it.fails('Given_a_body_that_is_not_JSON_When_the_test_finishes_Then_it_fails', async () => {
        fakeFetch(() => jsonResponse({}));

        await fetch('https://ledger.test/api/v1/accounts', {
            method: 'POST',
            body: 'not json',
        }).catch(() => undefined);
    });

    it('Given_an_unplanned_request_When_the_test_finishes_Then_the_failure_names_it', () => {
        const failures = [
            'GET https://ledger.test/api/v1/accounts: unexpected request',
            'POST https://ledger.test/api/v1/transactions sent a body that is not JSON',
        ];

        expect(() => {
            assertNoUnplannedRequests(failures);
        }).toThrow(failures.join('\n'));
    });

    it('Given_every_request_was_planned_When_the_test_finishes_Then_it_passes', () => {
        expect(() => {
            assertNoUnplannedRequests([]);
        }).not.toThrow();
    });
});

describe('fakeFetch header recording', () => {
    it('Given_a_request_with_headers_When_recorded_Then_their_names_are_lower_cased', async () => {
        const requests = fakeFetch(() => jsonResponse({}));

        await fetch('https://ledger.test/api/v1/accounts', {
            headers: { 'X-FINASSIST-XSRF-TOKEN': 'abc', 'Content-Type': 'application/json' },
        });

        // HTTP header names are case-insensitive; normalising here means a test asserting on
        // 'x-finassist-xsrf-token' cannot pass or fail on the caller's own capitalisation.
        expect(requests[0]?.headers).toEqual({
            'x-finassist-xsrf-token': 'abc',
            'content-type': 'application/json',
        });
    });

    it('Given_a_request_with_no_headers_When_recorded_Then_headers_is_empty', async () => {
        const requests = fakeFetch(() => jsonResponse({}));

        await fetch('https://ledger.test/api/v1/accounts');

        expect(requests[0]?.headers).toEqual({});
    });

    it('Given_a_recorded_request_When_read_as_RecordedRequest_Then_headers_is_part_of_its_shape', async () => {
        // A type-level check as much as a runtime one: a caller destructuring RecordedRequest
        // must see `headers` without an `as` cast.
        let seen: RecordedRequest | undefined;
        const requests = fakeFetch((request) => {
            seen = request;
            return jsonResponse({});
        });

        await fetch('https://ledger.test/api/v1/accounts', { headers: { 'X-Trace-Id': 'abc' } });

        expect(seen).toBe(requests[0]);
        expect(seen?.headers['x-trace-id']).toBe('abc');
    });
});
