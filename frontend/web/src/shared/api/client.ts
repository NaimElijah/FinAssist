/**
 * The one place the app talks HTTP.
 *
 * Once `contracts/` holds an OpenAPI document, the typed client is generated beside this file
 * and this wrapper is what it builds on. Until then it carries no endpoint knowledge, because
 * there are no endpoints.
 *
 * Being the single chokepoint is also why correlation lives here: every request carries a
 * `traceparent`, so the backends continue the browser's trace rather than starting their own,
 * and every failure is logged once with that id attached. See ADR-019.
 */

import { newTraceparent, traceIdOf } from '../lib/correlation';
import { log } from '../lib/logger';

/** W3C trace context. Named here so a caller overriding it is doing so deliberately. */
export const TRACEPARENT_HEADER = 'traceparent';

/** Thrown for any non-2xx response, so a failed call can never be mistaken for an empty one. */
export class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly url: string,
        message: string,
    ) {
        super(message);
        this.name = 'ApiError';
    }
}

/** Thrown when a 2xx response does not carry the JSON body the caller was promised. */
export class ApiBodyError extends Error {
    constructor(
        readonly url: string,
        message: string,
        options?: { cause?: unknown },
    ) {
        super(message, options);
        this.name = 'ApiBodyError';
    }
}

export async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
    // Built through the Headers API rather than object spread: HeadersInit is also allowed to
    // be a Headers instance or an array of pairs, neither of which spreads into an object.
    const headers = new Headers(init?.headers);
    headers.set('Accept', 'application/json');

    // A caller that already has a trace keeps it; only an uncorrelated request starts one.
    if (!headers.has(TRACEPARENT_HEADER)) {
        headers.set(TRACEPARENT_HEADER, newTraceparent());
    }
    const traceId = traceIdOf(headers.get(TRACEPARENT_HEADER));

    let response: Response;
    try {
        response = await fetch(url, { ...init, headers });
    } catch (cause) {
        // A transport failure never reaches the checks below, so it would otherwise be the one
        // failure mode that produced no record at all.
        log.error('http request failed', { url, traceId, reason: String(cause) });
        throw cause;
    }

    if (!response.ok) {
        log.error('http request returned an error status', {
            url,
            traceId,
            status: response.status,
        });
        throw new ApiError(response.status, url, `Request failed with ${String(response.status)}`);
    }

    // A 204, a 205, or a proxy returning an HTML page all satisfy `response.ok`. Letting
    // response.json() throw a raw SyntaxError here would break the contract stated above:
    // a caller catching ApiError would never see it.
    if (response.status === 204 || response.status === 205) {
        log.error('http response carried no body', { url, traceId, status: response.status });
        throw new ApiBodyError(url, `Expected a JSON body but got ${String(response.status)}`);
    }

    try {
        return (await response.json()) as T;
    } catch (cause) {
        log.error('http response body was not json', { url, traceId });
        throw new ApiBodyError(url, 'Response body was not valid JSON', { cause });
    }
}
