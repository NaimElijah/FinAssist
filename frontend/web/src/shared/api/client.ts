/**
 * The one place the app talks HTTP.
 *
 * Once `contracts/` holds an OpenAPI document, the typed client is generated beside this file
 * and this wrapper is what it builds on. Until then it carries no endpoint knowledge, because
 * there are no endpoints.
 */

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

    const response = await fetch(url, { ...init, headers });

    if (!response.ok) {
        throw new ApiError(response.status, url, `Request failed with ${String(response.status)}`);
    }

    // A 204, a 205, or a proxy returning an HTML page all satisfy `response.ok`. Letting
    // response.json() throw a raw SyntaxError here would break the contract stated above:
    // a caller catching ApiError would never see it.
    if (response.status === 204 || response.status === 205) {
        throw new ApiBodyError(url, `Expected a JSON body but got ${String(response.status)}`);
    }

    try {
        return (await response.json()) as T;
    } catch (cause) {
        throw new ApiBodyError(url, 'Response body was not valid JSON', { cause });
    }
}
