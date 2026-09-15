// @vitest-environment node
// No DOM is needed: fetch, Headers and Response are Node globals, and requestJson touches
// neither document nor window. Keeping this out of jsdom keeps the suite honest about what
// the code under test actually depends on.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { traceIdOf } from '../lib/correlation';
import { type LogRecord, setLogSink } from '../lib/logger';
import {
    ApiBodyError,
    ApiError,
    ASSISTANT_API,
    LEDGER_API,
    type Problem,
    requestJson,
    sendJson,
    TRACEPARENT_HEADER,
} from './client';

const URL = 'https://ledger.test/api/v1/accounts';

/**
 * Installs a stubbed global fetch and hands back the spy, typed as the real thing.
 *
 * Takes a factory rather than a Response, and builds a fresh one per call. A Response body can
 * only be read once, so handing the same instance to two calls makes the second fail with
 * "body already read" - a TypeError - instead of the failure the test means to provoke. That
 * is a test passing for the wrong reason, which is the thing this file exists to prevent.
 */
function stubFetch(response: () => Response): ReturnType<typeof vi.fn<typeof fetch>> {
    const stub = vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(response()));
    vi.stubGlobal('fetch', stub);
    return stub;
}

function jsonResponse(body: unknown, status = 200): () => Response {
    return () =>
        new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/json' },
        });
}

/** An RFC 9457 problem response, `application/problem+json`, as both services answer failures. */
function problemResponse(body: unknown, status: number): () => Response {
    return () =>
        new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/problem+json' },
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
        stubFetch(() => new Response('nope', { status: 503 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);
        await expect(requestJson(URL)).rejects.toMatchObject({ status: 503, url: URL });
    });

    it('Given_a_404_When_requested_Then_it_is_an_ApiError_and_never_an_empty_body', async () => {
        // The distinction the class comment insists on: a failed call must never be mistaken
        // for an empty one, so a 404 is an ApiError rather than an ApiBodyError.
        stubFetch(() => new Response('', { status: 404 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);
    });

    it('Given_a_204_When_requested_Then_an_ApiBodyError_is_thrown', async () => {
        stubFetch(() => new Response(null, { status: 204 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);
    });

    it('Given_a_205_When_requested_Then_an_ApiBodyError_is_thrown', async () => {
        stubFetch(() => new Response(null, { status: 205 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);
    });

    it('Given_a_body_that_is_not_json_When_requested_Then_an_ApiBodyError_names_the_cause', async () => {
        // A proxy answering 200 with an HTML error page is the case this guards: letting
        // response.json() throw a raw SyntaxError would break the contract, because a caller
        // catching ApiError would never see it.
        stubFetch(() => new Response('<html>gateway</html>', { status: 200 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);
        // The cause is asserted, not just the type: it is what tells a reader the body failed
        // to parse rather than, say, the body having already been consumed.
        await expect(requestJson(URL)).rejects.toMatchObject({
            url: URL,
            cause: expect.any(SyntaxError) as unknown,
        });
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

describe('correlation', () => {
    async function sentHeaders(init?: RequestInit): Promise<Headers> {
        const stub = stubFetch(jsonResponse({}));
        await requestJson(URL, init);
        const [, sent] = stub.mock.calls[0] ?? [];
        return new Headers(sent?.headers);
    }

    it('Given_no_traceparent_When_requested_Then_one_is_generated', async () => {
        const traceparent = (await sentHeaders()).get(TRACEPARENT_HEADER);

        expect(traceIdOf(traceparent)).toMatch(/^[0-9a-f]{32}$/);
    });

    it('Given_a_caller_supplied_traceparent_When_requested_Then_it_is_not_replaced', async () => {
        // Replacing it would break the trace at the boundary it exists to cross.
        const existing = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';

        const headers = await sentHeaders({ headers: { traceparent: existing } });

        expect(headers.get(TRACEPARENT_HEADER)).toBe(existing);
    });

    it('Given_two_requests_When_made_Then_each_carries_its_own_trace', async () => {
        const first = traceIdOf((await sentHeaders()).get(TRACEPARENT_HEADER));
        vi.unstubAllGlobals();
        const second = traceIdOf((await sentHeaders()).get(TRACEPARENT_HEADER));

        expect(first).not.toBe(second);
    });
});

describe('failure logging', () => {
    let written: LogRecord[] = [];
    let restore: () => void = () => undefined;

    beforeEach(() => {
        written = [];
        restore = setLogSink((record) => written.push(record));
    });

    afterEach(() => {
        restore();
    });

    function events(): unknown[] {
        return written.map((record) => record.event);
    }

    it('Given_an_error_status_When_requested_Then_it_is_logged_with_the_trace_id', async () => {
        stubFetch(jsonResponse({}, 500));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);

        expect(events()).toEqual(['http request returned an error status']);
        expect(written[0]).toMatchObject({ level: 'error', url: URL, status: 500 });
        expect(String(written[0]?.traceId)).toMatch(/^[0-9a-f]{32}$/);
    });

    it.each([400, 404, 409, 422])(
        'Given_a_%i_refusal_When_requested_Then_it_is_logged_at_info_with_the_same_fields',
        async (status) => {
            // A 4xx is the server's considered answer: the UI shows it and nobody has to act on
            // it, so it must not raise an application error in the console.
            stubFetch(problemResponse({ status, code: 'REFUSED' }, status));

            await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);

            expect(written).toHaveLength(1);
            expect(written[0]).toMatchObject({
                level: 'info',
                event: 'http request returned an error status',
                url: URL,
                status,
                code: 'REFUSED',
            });
            expect(String(written[0]?.traceId)).toMatch(/^[0-9a-f]{32}$/);
        },
    );

    it.each([500, 502, 503])(
        'Given_a_%i_server_failure_When_requested_Then_it_is_logged_at_error',
        async (status) => {
            stubFetch(() => new Response('', { status }));

            await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);

            expect(written).toHaveLength(1);
            expect(written[0]).toMatchObject({
                level: 'error',
                event: 'http request returned an error status',
                status,
            });
        },
    );

    it('Given_a_transport_failure_When_requested_Then_it_is_logged_at_error', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn<typeof fetch>()
                .mockImplementation(() => Promise.reject(new TypeError('offline'))),
        );

        await expect(requestJson(URL)).rejects.toBeInstanceOf(TypeError);

        expect(written.map((record) => record.level)).toEqual(['error']);
    });

    it.each([204, 205])(
        'Given_a_body_less_%i_When_requested_Then_it_is_logged_at_error',
        async (status) => {
            stubFetch(() => new Response(null, { status }));

            await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);

            expect(written.map((record) => record.level)).toEqual(['error']);
        },
    );

    it('Given_a_transport_failure_When_requested_Then_it_is_logged_and_rethrown', async () => {
        // The one failure mode that reaches none of the status checks, so without its own
        // branch it would be the only one producing no record at all.
        const boom = new TypeError('network down');
        vi.stubGlobal(
            'fetch',
            vi.fn<typeof fetch>().mockImplementation(() => Promise.reject(boom)),
        );

        await expect(requestJson(URL)).rejects.toBe(boom);

        expect(events()).toEqual(['http request failed']);
    });

    it('Given_a_204_When_requested_Then_the_missing_body_is_logged', async () => {
        stubFetch(() => new Response(null, { status: 204 }));

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);

        expect(events()).toEqual(['http response carried no body']);
    });

    it('Given_a_non_json_body_When_requested_Then_it_is_logged', async () => {
        stubFetch(
            () =>
                new Response('<html>a proxy error page</html>', {
                    status: 200,
                    headers: { 'Content-Type': 'text/html' },
                }),
        );

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiBodyError);

        expect(events()).toEqual(['http response body was not json']);
    });

    it('Given_a_successful_request_When_made_Then_nothing_is_logged', async () => {
        stubFetch(jsonResponse({ ok: true }));

        await requestJson(URL);

        expect(written).toEqual([]);
    });

    it('Given_a_problem_body_When_requested_Then_code_is_logged_but_detail_never_is', async () => {
        // detail can carry the owner's own descriptions - account names, memo text - which is
        // financial data (ADR-019). code is a stable identifier and safe to log.
        stubFetch(
            problemResponse(
                {
                    status: 409,
                    code: 'DUPLICATE_ACCOUNT_NAME',
                    detail: 'An account named "Checking" already exists.',
                },
                409,
            ),
        );

        await expect(requestJson(URL)).rejects.toBeInstanceOf(ApiError);

        expect(written[0]).toMatchObject({ code: 'DUPLICATE_ACCOUNT_NAME' });
        expect(written[0]).not.toHaveProperty('detail');
    });
});

describe('problem responses', () => {
    it('Given_a_problem_body_When_requested_Then_it_is_parsed_onto_ApiError_and_the_message_is_the_detail', async () => {
        const problem: Problem = {
            status: 422,
            code: 'ACCOUNT_CURRENCY_MISMATCH',
            title: 'Unprocessable Entity',
            detail: 'Entries must share one currency.',
        };
        stubFetch(problemResponse(problem, 422));

        await expect(requestJson(URL)).rejects.toMatchObject({
            problem,
            message: problem.detail,
        });
    });

    it('Given_an_error_response_with_a_plain_json_content_type_When_requested_Then_problem_is_undefined', async () => {
        // The body would satisfy isProblem, but the content type does not say problem+json, so
        // it is read as an ordinary error body, not a problem.
        stubFetch(jsonResponse({ status: 500, code: 'INTERNAL' }, 500));

        await expect(requestJson(URL)).rejects.toMatchObject({ problem: undefined });
    });

    it('Given_a_problem_content_type_but_a_body_missing_code_When_requested_Then_problem_is_undefined', async () => {
        stubFetch(problemResponse({ status: 400, title: 'Bad Request' }, 400));

        await expect(requestJson(URL)).rejects.toMatchObject({ problem: undefined });
    });

    it('Given_a_problem_content_type_but_an_unparseable_body_When_requested_Then_reading_it_does_not_throw_a_second_error', async () => {
        // A truncated or otherwise unreadable body must not turn one failure (the error status)
        // into two: the call still rejects with a plain ApiError, problem simply undefined.
        stubFetch(
            () =>
                new Response('not json', {
                    status: 500,
                    headers: { 'Content-Type': 'application/problem+json' },
                }),
        );

        await expect(requestJson(URL)).rejects.toMatchObject({ problem: undefined, status: 500 });
    });

    it('Given_a_problem_with_a_title_but_no_detail_When_requested_Then_the_message_is_the_title', async () => {
        const problem: Problem = {
            status: 409,
            code: 'DUPLICATE_ACCOUNT_NAME',
            title: 'Conflict',
        };
        stubFetch(problemResponse(problem, 409));

        await expect(requestJson(URL)).rejects.toMatchObject({ problem, message: 'Conflict' });
    });
});

/** Narrows a caught value to `ApiError`, without an `as` cast on what a `.catch` hands back. */
function isApiError(value: unknown): value is ApiError {
    return value instanceof ApiError;
}

describe('problem field errors', () => {
    it('Given_a_problem_with_a_well_formed_errors_array_When_requested_Then_it_is_kept', async () => {
        const problem: Problem = {
            status: 400,
            code: 'VALIDATION_FAILED',
            errors: [{ field: 'amount', message: 'must be positive' }],
        };
        stubFetch(problemResponse(problem, 400));

        const failure = await requestJson(URL).catch((caught: unknown) => caught);
        if (!isApiError(failure)) {
            throw new Error('expected requestJson to reject with an ApiError');
        }

        expect(failure.problem).toEqual(problem);
    });

    it('Given_a_problem_with_a_malformed_errors_entry_When_requested_Then_errors_is_omitted_but_the_rest_of_the_problem_is_kept', async () => {
        stubFetch(
            problemResponse(
                {
                    status: 400,
                    code: 'VALIDATION_FAILED',
                    title: 'Bad Request',
                    // `message` is missing, so every() over the field-error shape must reject it.
                    errors: [{ field: 'amount' }],
                },
                400,
            ),
        );

        const failure = await requestJson(URL).catch((caught: unknown) => caught);
        if (!isApiError(failure)) {
            throw new Error('expected requestJson to reject with an ApiError');
        }

        expect(failure.problem).toEqual({
            status: 400,
            code: 'VALIDATION_FAILED',
            title: 'Bad Request',
        });
    });
});

describe('sendJson', () => {
    it('Given_a_body_When_sent_Then_it_posts_json_with_a_content_type_and_a_traceparent', async () => {
        const stub = stubFetch(jsonResponse({ id: 'txn-1' }, 201));

        await sendJson(URL, 'POST', { amount: '12.50' });

        const [, sent] = stub.mock.calls[0] ?? [];
        const headers = new Headers(sent?.headers);
        expect(sent?.method).toBe('POST');
        expect(sent?.body).toBe(JSON.stringify({ amount: '12.50' }));
        expect(headers.get('Content-Type')).toBe('application/json');
        expect(traceIdOf(headers.get(TRACEPARENT_HEADER))).toMatch(/^[0-9a-f]{32}$/);
    });

    it('Given_a_201_response_When_sent_Then_the_created_body_is_resolved', async () => {
        stubFetch(jsonResponse({ id: 'txn-1' }, 201));

        await expect(sendJson(URL, 'POST', { amount: '12.50' })).resolves.toEqual({
            id: 'txn-1',
        });
    });
});

describe('base paths', () => {
    it('Given_the_module_When_read_Then_LEDGER_API_and_ASSISTANT_API_are_the_proxied_v1_paths', () => {
        expect(LEDGER_API).toBe('/ledger/api/v1');
        expect(ASSISTANT_API).toBe('/assistant/api/v1');
    });
});
