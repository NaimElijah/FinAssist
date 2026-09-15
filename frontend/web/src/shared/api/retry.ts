/**
 * Which failed requests a second attempt could answer differently: the one rule behind both the
 * query client's automatic retry and the retry a failed read's error state offers, so the two
 * never disagree.
 */
import { ApiError } from './client';

/** The lowest status meaning the server failed, rather than that it refused the request. */
const FIRST_SERVER_ERROR_STATUS = 500;

/**
 * The one 5xx that is a considered answer rather than a failure: the assistant's
 * `PRICE_UNAVAILABLE`, a price feed gone stale. A stale feed does not recover within a retry's
 * backoff, so a retry would only delay showing that no price is usable.
 */
const SERVICE_UNAVAILABLE_STATUS = 503;

/**
 * Whether a failed request could meet a different outcome if it were sent again.
 *
 * That means a network failure, which `fetch` reports as a `TypeError` and `requestJson` rethrows,
 * or a 5xx `ApiError` other than a 503. A backend restarting refuses the connection, which reaches
 * here as a `TypeError`, or through the dev proxy as a 502. A 4xx is the server's considered
 * answer - a 404 stays missing and a 409 stays in conflict - so a second attempt would only repeat
 * it, and a 503 is considered too: see `SERVICE_UNAVAILABLE_STATUS`. Anything else, such as an
 * `ApiBodyError`, is a defect a second attempt would repeat. A `TypeError` thrown by a defect in
 * the code handling a response counts as retryable as well; from here the two cannot be told apart.
 *
 * @param error - whatever the failed request threw.
 * @returns whether sending the request again could succeed.
 */
export function isRetryable(error: unknown): boolean {
    if (error instanceof ApiError) {
        return (
            error.status >= FIRST_SERVER_ERROR_STATUS && error.status !== SERVICE_UNAVAILABLE_STATUS
        );
    }
    return error instanceof TypeError;
}
