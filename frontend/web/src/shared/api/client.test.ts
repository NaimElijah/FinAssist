// @vitest-environment node
// No DOM is needed: fetch, Headers and Response are Node globals, and requestJson touches
// neither document nor window. Keeping this out of jsdom keeps the suite honest about what
// the code under test actually depends on.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError, requestJson } from './client';

const URL = 'https://ledger.test/api/v1/accounts';

/** Installs a stubbed global fetch and hands back the spy, typed as the real thing. */
function stubFetch(response: Response): ReturnType<typeof vi.fn<typeof fetch>> {
    const stub = vi.fn<typeof fetch>().mockResolvedValue(response);
    vi.stubGlobal('fetch', stub);
    return stub;
}

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('requestJson', () => {
    it('Given_a_successful_response_When_requested_Then_the_body_is_parsed', async () => {
        stubFetch(jsonResponse({ id: 'acc-1', name: 'Checking' }));

        await expect(requestJson<{ id: string }>(URL)).resolves.toEqual({
            id: 'acc-1',
            name: 'Checking',
        });
    });

    it('Given_a_non_2xx_response_When_requested_Then_an_ApiError_carries_the_status_and_url', async () => {
        stubFetch(new Response('nope', { status: 503 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);
        await expect(requestJson(URL)).rejects.toMatchObject({ status: 503, url: URL });
    });

    it('Given_a_404_When_requested_Then_it_is_an_ApiError_and_never_an_empty_body', async () => {
        // The distinction the class comment insists on: a failed call must never be mistaken
        // for an empty one, so a 404 is an ApiError rather than an ApiBodyError.
        stubFetch(new Response('', { status: 404 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);
    });

    it('Given_a_204_When_requested_Then_an_ApiBodyError_is_thrown', async () => {
        stubFetch(new Response(null, { status: 204 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);
    });

    it('Given_a_205_When_requested_Then_an_ApiBodyError_is_thrown', async () => {
        stubFetch(new Response(null, { status: 205 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);
    });

    it('Given_a_body_that_is_not_json_When_requested_Then_an_ApiBodyError_names_the_cause', async () => {
        // A proxy answering 200 with an HTML error page is the case this guards: letting
        // response.json() throw a raw SyntaxError would break the contract, because a caller
        // catching ApiError would never see it.
        stubFetch(new Response('<html>gateway</html>', { status: 200 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);
        await expect(requestJson(URL)).rejects.toMatchObject({ url: URL });
    });
});

describe('the Accept header', () => {
    // HeadersInit is allowed to be a plain object, a Headers instance, or an array of pairs.
    // Object spread silently drops the last two - the bug ADR-009 cites as the payoff of the
    // type-aware lint rules, and which nothing tested until now.

    async function acceptHeaderFor(init?: RequestInit): Promise<string | null> {
        const stub = stubFetch(jsonResponse({}));
        await requestJson(URL, init);
        const [, sent] = stub.mock.calls[0] ?? [];
        return new Headers(sent?.headers).get('Accept');
    }

    it('Given_no_headers_When_requested_Then_accept_is_set', async () => {
        await expect(acceptHeaderFor()).resolves.toBe('application/json');
    });

    it('Given_headers_as_a_plain_object_When_requested_Then_accept_is_still_set', async () => {
        await expect(acceptHeaderFor({ headers: { 'X-Trace-Id': 'abc' } })).resolves.toBe(
            'application/json',
        );
    });

    it('Given_headers_as_a_Headers_instance_When_requested_Then_accept_is_still_set', async () => {
        const headers = new Headers({ 'X-Trace-Id': 'abc' });

        await expect(acceptHeaderFor({ headers })).resolves.toBe('application/json');
    });

    it('Given_headers_as_an_array_of_pairs_When_requested_Then_accept_is_still_set', async () => {
        await expect(acceptHeaderFor({ headers: [['X-Trace-Id', 'abc']] })).resolves.toBe(
            'application/json',
        );
    });

    it('Given_headers_carrying_other_values_When_requested_Then_they_survive', async () => {
        const stub = stubFetch(jsonResponse({}));
        await requestJson(URL, { headers: { 'X-Trace-Id': 'abc' }, method: 'POST' });
        const [, sent] = stub.mock.calls[0] ?? [];

        expect(new Headers(sent?.headers).get('X-Trace-Id')).toBe('abc');
        expect(sent?.method).toBe('POST');
    });
});
