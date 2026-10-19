/**
 * What a form does with its last answer once the owner edits the form again.
 */

/**
 * The parts of a mutation `clearSettledOutcome` reads. TanStack Query's `useMutation` result has
 * both, and nothing here names the library.
 */
export interface SettleableMutation {
    readonly status: 'idle' | 'pending' | 'error' | 'success';
    readonly reset: () => void;
}

/**
 * A form's in-flight guard: set from the moment a submit starts a request until that request
 * settles. A `useRef<boolean>` is one.
 */
export interface InFlightGuard {
    readonly current: boolean;
}

/**
 * Clear what a settled mutation answered - its error, or its result - when the owner edits the form
 * that sent it, so a screen never shows an answer beside inputs it no longer answers.
 *
 * A mutation still in flight is left alone. Resetting one detaches the form from it, so the
 * callbacks its `mutate` was given would never run - among them the `onSettled` that releases a
 * form's in-flight guard - and the form would stay locked.
 *
 * Whether a request is in flight is the guard's to say, not `status`'s. The status is the one the
 * form last rendered, and a resubmit's pending status renders a tick after the submit starts it, so
 * for that tick the status still reads the last answer while a request is running (#71). The guard
 * is set synchronously by the submit, for the same reason a form's double-submit check reads it.
 *
 * @param mutation - the form's mutation.
 * @param inFlight - the form's in-flight guard.
 */
export function clearSettledOutcome(mutation: SettleableMutation, inFlight: InFlightGuard): void {
    if (inFlight.current) {
        return;
    }
    if (mutation.status === 'error' || mutation.status === 'success') {
        mutation.reset();
    }
}
