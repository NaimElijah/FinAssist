// @vitest-environment node
// fetch, Headers and Response are Node globals, and this file exercises fakeFetch itself
// rather than a browser-only caller, so no DOM is needed here either.

import { describe, expect, it } from 'vitest';

import { fakeFetch, jsonResponse, type RecordedRequest } from './fakeFetch';

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
