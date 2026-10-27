/**
 * A fake backend at the `fetch` boundary, for component tests.
 *
 * Stubbing `fetch` rather than a feature's `api/` module keeps that module, and its wire-to-feature
 * mapping, under test (ADR-029). Generic on purpose: it knows no URL and imports no feature.
 *
 * A mistake in a test must not pass quietly. Inside the app, whatever the fake throws is caught by
 * the HTTP client and becomes an error state, which a test asserting on something else never
 * notices. So the fake records it - a request its answer does not plan for, or a body that is not
 * JSON - and fails the test when the test finishes.
 */
import { onTestFinished, vi } from 'vitest';

/** One request the code under test made, as the fake saw it. */
export interface RecordedRequest {
    readonly method: string;
    readonly url: string;
    /**
     * Every header the request carried, keyed by lower-cased name. HTTP header names are
     * case-insensitive, and the `Headers` interface already normalises them that way, so a
     * test asserting on, say, `x-finassist-xsrf-token` cannot pass or fail on whatever
     * capitalisation the caller happened to use.
     */
    readonly headers: Readonly<Record<string, string>>;
    /** The parsed JSON body, or `undefined` for a request without one. */
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

/** Builds the lower-cased header map `RecordedRequest.headers` carries, from `init.headers`. */
function headersOf(init: RequestInit | undefined): Readonly<Record<string, string>> {
    return Object.fromEntries(new Headers(init?.headers).entries());
}

/**
 * The check `fakeFetch` runs when a test finishes, exported so the double's own guarantee can be
 * tested without a test of its own failing.
 *
 * @param failures - one line per request the fake could not serve as planned, naming it.
 * @throws Error - when there is any, listing every one.
 */
export function assertNoUnplannedRequests(failures: readonly string[]): void {
    if (failures.length > 0) {
        throw new Error(
            `fakeFetch received requests the test did not plan for:\n${failures.join('\n')}`,
        );
    }
}

/**
 * Replace the global `fetch` with one answering every request through `answer`, for the rest of
 * the current test.
 *
 * When the test finishes, the real `fetch` is restored, so a caller needs no
 * `vi.unstubAllGlobals()` of its own. The test then fails if `answer` threw for any request, or
 * any request carried a body that is not a JSON string. The request still fails inside the app
 * as it would have, so the error the app shows is unchanged; only the test learns of it too.
 *
 * Call it from the body of a test: it registers an `onTestFinished` hook.
 *
 * @param answer - builds the response for each request, a fresh one per call, since a Response
 *   body can be read only once.
 * @returns every request received, in order, appended to as the test runs.
 */
export function fakeFetch(answer: FetchAnswer): readonly RecordedRequest[] {
    const requests: RecordedRequest[] = [];
    const failures: string[] = [];

    onTestFinished(() => {
        vi.unstubAllGlobals();
        assertNoUnplannedRequests(failures);
    });

    function parsedBody(body: BodyInit | null | undefined, label: string): unknown {
        if (body === undefined || body === null) {
            return undefined;
        }
        if (typeof body !== 'string') {
            failures.push(`${label} sent a body that is not a string`);
            return undefined;
        }
        try {
            return JSON.parse(body) as unknown;
        } catch {
            failures.push(`${label} sent a body that is not JSON`);
            return undefined;
        }
    }

    vi.stubGlobal(
        'fetch',
        vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
            const method = init?.method ?? 'GET';
            const url = urlOf(input);
            const label = `${method} ${url}`;
            const request: RecordedRequest = {
                method,
                url,
                headers: headersOf(init),
                body: parsedBody(init?.body, label),
            };
            requests.push(request);
            try {
                return await answer(request);
            } catch (error) {
                failures.push(
                    `${label}: ${error instanceof Error ? error.message : 'a non-Error was thrown'}`,
                );
                throw error;
            }
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
 * Refuse a request the test did not plan for. `fakeFetch` records the refusal and fails the test
 * when it finishes.
 *
 * @throws Error - always, naming the method and URL.
 */
export function unexpectedRequest(request: RecordedRequest): never {
    throw new Error(`unexpected request ${request.method} ${request.url}`);
}
