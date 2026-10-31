/**
 * The one place the app talks HTTP.
 *
 * Every feature's `api/` module builds its calls on `requestJson` and `sendJson`, typing each
 * response with the types generated from `contracts/` into `src/shared/api/contracts/`. No client
 * is generated (ADR-029), so this wrapper knows no endpoint beyond the two base paths below.
 *
 * Being the single chokepoint is also why correlation lives here: every request carries a
 * `traceparent`, so the backends continue the browser's trace rather than starting their own,
 * and every failure is logged once with that id attached. A 4xx is logged at `info`: it is the
 * server's considered answer, the UI shows it, and nobody has to act on it. A 5xx, a transport
 * failure and a 2xx without the JSON body it promised are logged at `error`. See ADR-019.
 *
 * It is also where a failure's RFC 9457 `application/problem+json` body is parsed onto
 * `ApiError`, so a caller can show the server's message and branch on its stable `code` rather
 * than reparsing the response itself. See ADR-028.
 */

import { ASSISTANT_BACKEND, BFF_PREFIX, LEDGER_BACKEND } from './backends';
import { newTraceparent, traceIdOf } from '../lib/correlation';
import { log } from '../lib/logger';

/** W3C trace context. Named here so a caller overriding it is doing so deliberately. */
export const TRACEPARENT_HEADER = 'traceparent';

/**
 * The gateway's CSRF cookie, script-readable so this client can echo its value back (ADR-030).
 * Renamed from Spring Security's default: cookies ignore ports, and the default name would
 * collide with another app on localhost.
 */
const CSRF_COOKIE_NAME = 'FINASSIST-XSRF-TOKEN';

/** The header a state-changing request must echo the CSRF cookie's value into (ADR-030). */
export const CSRF_HEADER = 'X-FINASSIST-XSRF-TOKEN';

/** The ledger's base path, reached through the dev/preview proxy (ADR-026). */
export const LEDGER_API = `${LEDGER_BACKEND.prefix}/api/v1`;

/** The assistant's base path, reached through the dev/preview proxy (ADR-026). */
export const ASSISTANT_API = `${ASSISTANT_BACKEND.prefix}/api/v1`;

/**
 * The gateway's own base path - sign-in, callback, `/bff/me` and `/bff/logout` - reached through
 * the same dev/preview proxy (ADR-026, ADR-030). Unlike `LEDGER_API` and `ASSISTANT_API`, nothing
 * under it carries a versioned segment, so this is `BFF_PREFIX` itself rather than a suffix of it.
 */
export const BFF_API = BFF_PREFIX;

/**
 * One field-level validation failure inside `Problem.errors` (ADR-028: populated only for a 400).
 */
export interface ProblemFieldError {
    readonly field: string;
    readonly message: string;
}

/**
 * An RFC 9457 problem, the shape both services answer every failure with (ADR-028).
 *
 * `title` and `detail` are prose for a person; `code` is the stable member a caller branches
 * on. `errors` is populated only for a 400 validation failure, one entry per invalid field.
 */
export interface Problem {
    readonly type?: string;
    readonly title?: string;
    readonly status: number;
    readonly detail?: string;
    readonly instance?: string;
    readonly code: string;
    readonly errors?: readonly ProblemFieldError[];
}

/**
 * Narrows `value` to a plain object, so a candidate field can be read off it without an `as`
 * cast on data that arrived as unvalidated JSON.
 */
function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

/**
 * Validate a parsed JSON body as a `Problem`, at the boundary where it arrives from the network.
 *
 * Checks only the two fields the client depends on to recognise a problem at all - `code` is a
 * string, the member ADR-028 has a client branch on, and `status` is a number - both of which
 * every contract requires. It reads nothing else, so it checks nothing else: a body missing
 * `type` or `title`, which the contracts also require, or `detail` or `errors`, which they do
 * not, still validates. `errors` is deliberately not checked here: a malformed one must not
 * disqualify the rest of the problem, so it is validated separately, by `readProblem`, once this
 * guard has already decided the body is a problem.
 *
 * @param value - a parsed JSON body of unknown shape.
 * @returns whether `value` is a `Problem`.
 */
export function isProblem(value: unknown): value is Problem {
    return isRecord(value) && typeof value.status === 'number' && typeof value.code === 'string';
}

/** Whether `value` is one well-formed entry of `Problem.errors`: a string `field` and `message`. */
function isProblemFieldError(value: unknown): value is ProblemFieldError {
    return isRecord(value) && typeof value.field === 'string' && typeof value.message === 'string';
}

/**
 * Validate a candidate `errors` value against `ProblemFieldError`'s shape.
 *
 * `isProblem` never checks this field, so a value whose static type already claims to be a
 * valid `errors` array - because it came from something `isProblem` already narrowed to
 * `Problem` - may still not actually be one at runtime. Feature forms map field errors from
 * this array, so a malformed entry must never reach one.
 *
 * @param value - the candidate value, typically a problem's own `errors` property.
 * @returns the array, when every entry has a string `field` and a string `message`;
 *   `undefined` for anything else, including a missing or non-array value.
 */
function validatedErrors(value: unknown): readonly ProblemFieldError[] | undefined {
    return Array.isArray(value) && value.every(isProblemFieldError) ? value : undefined;
}

/** Thrown for any non-2xx response, so a failed call can never be mistaken for an empty one. */
export class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly url: string,
        message: string,
        /** The parsed problem, or `undefined` when the response carried none. */
        readonly problem: Problem | undefined,
    ) {
        super(message);
        this.name = 'ApiError';
    }
}

/**
 * Whether `error` is an `ApiError` whose problem carries `code`: how a screen tells one of a
 * service's considered answers, such as a missing account, from any other failure (ADR-028).
 *
 * @param error - whatever a request threw, or `null` when it has not failed.
 * @param code - the problem `code` to look for.
 * @returns `true` only for an `ApiError` with a parsed problem whose `code` is `code`.
 */
export function hasProblemCode(error: unknown, code: string): boolean {
    return error instanceof ApiError && error.problem?.code === code;
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

const PROBLEM_CONTENT_TYPE = 'application/problem+json';

const FIRST_CLIENT_ERROR_STATUS = 400;
const FIRST_SERVER_ERROR_STATUS = 500;

/** Whether `status` is a 4xx: a refusal the server meant to send, not a failure of the server. */
function isClientError(status: number): boolean {
    return status >= FIRST_CLIENT_ERROR_STATUS && status < FIRST_SERVER_ERROR_STATUS;
}

/**
 * Read a non-2xx response's body as a `Problem`, or `undefined` when it does not carry one.
 *
 * Reading the body can itself fail - it may be empty, truncated, or not JSON - and that must
 * never turn one failure into a second, unhandled one. Any read or parse error here is
 * swallowed and reported as `undefined`, exactly like a body that parses but fails `isProblem`.
 *
 * @param response - the non-2xx response being handled. Its body is not read again afterwards.
 * @returns the parsed problem, or `undefined` when the response carries none.
 */
async function readProblem(response: Response): Promise<Problem | undefined> {
    const contentType = response.headers.get('Content-Type');
    if (!contentType?.includes(PROBLEM_CONTENT_TYPE)) {
        return undefined;
    }
    try {
        const body: unknown = await response.json();
        if (!isProblem(body)) {
            return undefined;
        }
        // status, code and the rest are kept regardless of errors; only errors itself, once
        // independently validated, decides whether it is kept or omitted.
        const { errors: rawErrors, ...rest } = body;
        const errors = validatedErrors(rawErrors);
        return { ...rest, ...(errors === undefined ? {} : { errors }) };
    } catch {
        return undefined;
    }
}

/**
 * Send a request and read its JSON body: the path every call in the app takes, `sendJson`'s
 * included.
 *
 * `Accept: application/json` is always set, and a `traceparent` is added unless the caller already
 * sent one, so the backend continues the browser's trace. Every failure is logged once, with that
 * trace id and never a problem's `detail`, before it is thrown (ADR-019).
 *
 * @param url - the request URL, typically built from `LEDGER_API` or `ASSISTANT_API`.
 * @param init - further `RequestInit` fields; its headers may be any `HeadersInit` shape.
 * @returns the parsed JSON body, typed as the caller asks. It is not checked against the contract
 *   here: that is each feature's `api/` module's job.
 * @throws ApiError - on a non-2xx response, carrying the parsed problem when the body is one.
 * @throws ApiBodyError - when a 2xx response carries no body, or one that is not JSON.
 * @throws TypeError - rethrown from `fetch` when the request cannot be sent, such as a network
 *   failure.
 */
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
        const problem = await readProblem(response);
        // `detail` can carry the owner's own descriptions - an account name, memo text - which
        // is financial data (ADR-019: redaction happens at the boundary, and the safest
        // redaction is not logging it at all). `code` is a stable identifier, never that.
        // A refusal is recorded, not raised as an error: the owner's logging convention keeps
        // `error` for what someone must act on, and an expected refusal is not that.
        const record = isClientError(response.status) ? log.info : log.error;
        record('http request returned an error status', {
            url,
            traceId,
            status: response.status,
            ...(problem === undefined ? {} : { code: problem.code }),
        });
        const message =
            problem?.detail ?? problem?.title ?? `Request failed with ${String(response.status)}`;
        throw new ApiError(response.status, url, message, problem);
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

/**
 * Find the gateway's CSRF cookie in a raw cookie string and decode its value.
 *
 * Pure so it can be tested without a browser: the caller passes `document.cookie` in, the same
 * way the rest of this codebase passes a clock in rather than a function reading `now()` itself.
 *
 * `document.cookie` is `name=value` pairs separated by `; `. Each pair is split on its *first*
 * `=` only, since the value itself may contain one, and the name is matched exactly - a cookie
 * such as `FINASSIST-XSRF-TOKEN2` or `MY-FINASSIST-XSRF-TOKEN` is a different cookie, not this
 * one under a prefix or suffix match.
 *
 * The gateway only ever writes a token that needs no percent-encoding, so a value that fails to
 * decode can only mean a tampered or corrupted cookie, never a legitimate one this client wrote
 * itself. `decodeURIComponent` is left to throw here rather than caught: swallowing it would
 * silently send a corrupted token as though it were valid, and the repository's rule is to fail
 * loudly rather than return a sentinel in its place.
 *
 * @param cookieString - the raw cookie header, in practice always `document.cookie`.
 * @returns the decoded token, or `undefined` when the cookie is not present.
 * @throws URIError - when the cookie is present but its value is not valid percent-encoding.
 */
export function xsrfTokenFrom(cookieString: string): string | undefined {
    for (const pair of cookieString.split('; ')) {
        const separator = pair.indexOf('=');
        if (separator === -1) {
            continue;
        }
        if (pair.slice(0, separator) === CSRF_COOKIE_NAME) {
            return decodeURIComponent(pair.slice(separator + 1));
        }
    }
    return undefined;
}

/**
 * `requestJson` for a request carrying a JSON body.
 *
 * Both services refuse a write whose body is not `application/json`, answering 415 (ADR-030),
 * so this is the only path that writes: it sets `Content-Type: application/json` and
 * stringifies `body`, and otherwise behaves exactly like `requestJson` - the same `Accept`
 * header, the same `traceparent` propagation, the same error handling.
 *
 * It is also the only path that sends `CSRF_HEADER`: the gateway's CSRF protection only guards
 * state-changing requests, so a read carrying the same header would widen its exposure for no
 * gain. When the cookie is absent - signed out, or talking to a backend with no gateway in
 * front of it - no header is sent at all, and the gateway's own filter is what answers that.
 *
 * @param url - the request URL, typically built from `LEDGER_API` or `ASSISTANT_API`.
 * @param method - `POST`. Every write ADR-028 defines - a create, a lifecycle command, a
 *   side-effect-free computation carrying a monetary amount - is a `POST`, so a wider type would
 *   not describe an actual write.
 * @param body - serialised with `JSON.stringify` and sent as the request body.
 * @param init - additional `RequestInit` fields, merged the same way `requestJson` merges them.
 * @returns the parsed JSON response body, including a `201`'s created representation.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no parseable JSON body.
 * @throws URIError - propagated from `xsrfTokenFrom` when the CSRF cookie is present but
 *   corrupted.
 */
export async function sendJson<T>(
    url: string,
    method: 'POST',
    body: unknown,
    init?: RequestInit,
): Promise<T> {
    const headers = new Headers(init?.headers);
    headers.set('Content-Type', 'application/json');
    const csrfToken = xsrfTokenFrom(document.cookie);
    if (csrfToken !== undefined) {
        headers.set(CSRF_HEADER, csrfToken);
    }
    return requestJson<T>(url, { ...init, method, headers, body: JSON.stringify(body) });
}
