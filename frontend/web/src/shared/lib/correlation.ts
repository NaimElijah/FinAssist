/**
 * W3C trace context generation.
 *
 * The client sends a `traceparent` rather than a bespoke header so the backends continue the
 * same trace instead of starting their own. That is the whole reason for using the standard:
 * a browser request and the span the ledger records become one trace, not two ids to join by
 * hand. See ADR-019.
 *
 * No OpenTelemetry SDK is used. Generating the header is a few lines; the SDK's value is in
 * measuring real user timing, which needs real users.
 */

/** Version 00 is the only `traceparent` version defined by the W3C recommendation. */
const VERSION = '00';

/** `01` means sampled. The system samples everything, so this is never `00`. */
const SAMPLED_FLAGS = '01';

const TRACE_ID_BYTES = 16;
const SPAN_ID_BYTES = 8;

/** The W3C format reserves the all-zero trace id as invalid, so a header carrying it names none. */
const INVALID_TRACE_ID = '0'.repeat(TRACE_ID_BYTES * 2);

function randomHex(bytes: number): string {
    const buffer = new Uint8Array(bytes);
    crypto.getRandomValues(buffer);
    return Array.from(buffer, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/**
 * A fresh trace id: 16 random bytes as 32 lowercase hex characters.
 *
 * All-zero is invalid per the specification. It is not guarded against here because
 * `crypto.getRandomValues` producing 16 zero bytes has probability 2^-128.
 */
export function newTraceId(): string {
    return randomHex(TRACE_ID_BYTES);
}

/** A fresh span id: 8 random bytes as 16 lowercase hex characters. */
export function newSpanId(): string {
    return randomHex(SPAN_ID_BYTES);
}

/**
 * Build a `traceparent` header value for a new trace.
 *
 * @param traceId - trace to continue. Defaults to a new one, which is the usual case: the
 *   browser starts the trace because nothing upstream of it has.
 * @returns a `version-traceid-spanid-flags` string.
 */
export function newTraceparent(traceId: string = newTraceId()): string {
    return `${VERSION}-${traceId}-${newSpanId()}-${SAMPLED_FLAGS}`;
}

/**
 * Extract the trace id from a `traceparent`, or `null` if the value is not one.
 *
 * The assistant's `trace_id_of` applies the same rule, the all-zero id refused alike, so neither
 * side lets in an id the other would turn away.
 *
 * @param traceparent - a header value, possibly malformed or absent.
 * @returns the 32-character trace id, or `null` when the header cannot be parsed or carries the
 *   all-zero id.
 */
export function traceIdOf(traceparent: string | null | undefined): string | null {
    if (!traceparent) {
        return null;
    }
    const fields = traceparent.split('-');
    const traceId = fields[1];
    if (
        fields.length < 4 ||
        traceId === undefined ||
        !/^[0-9a-f]{32}$/.test(traceId) ||
        traceId === INVALID_TRACE_ID
    ) {
        return null;
    }
    return traceId;
}
