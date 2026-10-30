/**
 * One value placed in an API path as exactly one segment.
 *
 * `encodeURIComponent` alone keeps a `/` from splitting a value across two segments, but it
 * leaves `.` and `..` as they are, and the URL parser resolves those as dot segments: an id of
 * `..` in `holdings/../disposal` names a different route one level up. An empty value collapses
 * the path the same way. None of the three is a legal id or symbol, so each is refused before a
 * request is built rather than sent somewhere else.
 */

/** Values the URL parser resolves away rather than keeping as a segment of their own. */
const UNSAFE_SEGMENTS: ReadonlySet<string> = new Set(['', '.', '..']);

/**
 * `value`, percent-encoded as a single path segment.
 *
 * @param value - an id or symbol, typically a route parameter or one read from a response.
 * @returns `encodeURIComponent(value)`.
 * @throws RangeError - when `value` is empty, `.` or `..`. The message names none of them, so a
 *   value that arrived from a response is never echoed.
 */
export function pathSegment(value: string): string {
    if (UNSAFE_SEGMENTS.has(value)) {
        throw new RangeError('An API path segment may not be empty, "." or ".."');
    }
    return encodeURIComponent(value);
}
