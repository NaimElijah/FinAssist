/**
 * A fake backend at the `fetch` boundary, for component tests.
 *
 * Stubbing `fetch` rather than a feature's `api/` module keeps that module, and its wire-to-feature
 * mapping, under test (ADR-029). Generic on purpose: it knows no URL and imports no feature.
 */
import { vi } from 'vitest';

/** One request the code under test made, as the fake saw it. */
export interface RecordedRequest {
    readonly method: string;
    readonly url: string;
    /** The parsed JSON body, or `undefined` for a request without a string body. */
    readonly body: unknown;
}

/** Decides the response to one request. Throw, through `unexpectedRequest`, for one not planned. */
export type FetchAnswer = (request: RecordedRequest) => Response | Promise<Response>;

function urlOf(input: RequestInfo | URL): string {
    if (typeof input === 'string') {
        return input;
    }
    return input instanceof URL ? input.href : input.url;
}

/**
 * Replace the global `fetch` with one answering every request through `answer`.
 *
 * The caller restores the real one with `vi.unstubAllGlobals()` in an `afterEach`.
 *
 * @param answer - builds the response for each request, a fresh one per call, since a Response
 *   body can be read only once.
 * @returns every request received, in order, appended to as the test runs.
 */
export function fakeFetch(answer: FetchAnswer): readonly RecordedRequest[] {
    const requests: RecordedRequest[] = [];
    vi.stubGlobal(
        'fetch',
        vi.fn<typeof fetch>().mockImplementation((input, init) => {
            const body: unknown =
                typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
            const request: RecordedRequest = {
                method: init?.method ?? 'GET',
                url: urlOf(input),
                body,
            };
            requests.push(request);
            return Promise.resolve(answer(request));
        }),
    );
    return requests;
}

/**
 * A JSON response, as both services answer a success.
 *
 * @param body - serialised as the response body.
 * @param status - defaults to 200.
 */
export function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

/**
 * An RFC 9457 problem response, as both services answer a failure (ADR-028).
 *
 * @param problem - the body; its `status` is also the response status.
 */
export function problemResponse(
    problem: { readonly status: number; readonly code: string } & Record<string, unknown>,
): Response {
    return new Response(JSON.stringify(problem), {
        status: problem.status,
        headers: { 'Content-Type': 'application/problem+json' },
    });
}

/**
 * A response that never arrives, for holding a request in flight for the rest of a test.
 */
export function pendingResponse(): Promise<Response> {
    return new Promise<Response>(() => undefined);
}

/**
 * Refuse a request the test did not plan for, so a test never passes on a request it did not
 * mean to allow.
 *
 * @throws Error - always, naming the method and URL.
 */
export function unexpectedRequest(request: RecordedRequest): never {
    throw new Error(`unexpected request ${request.method} ${request.url}`);
}
