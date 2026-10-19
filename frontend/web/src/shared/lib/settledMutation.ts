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
 * that sent it, so that answer does not stand beside inputs it no longer answers.
 *
 * A mutation still in flight is left alone. Resetting one detaches the form from it, so the
 * callbacks its `mutate` was given would never run - among them the `onSettled` that releases a
 * form's in-flight guard - and the form would stay locked. Its answer, when it arrives, is shown
 * even if the inputs were edited meanwhile.
 *
 * Whether a request is in flight is the guard's to say, never `status`'s. The status is the one
 * the form last rendered, and it lags a tick both ways (#71). A resubmit's pending status renders
 * a tick after the submit starts it, so for that tick the status still reads the last answer while
 * a request is running. A settled status renders a tick after `onSettled` releases the guard, so
 * for that tick it still reads `pending` once every callback has run. The guard is set
 * synchronously by the submit, for the same reason a form's double-submit check reads it. The
 * status only spares a mutation never sent a reset that would change nothing.
 *
 * @param mutation - the form's mutation.
 * @param inFlight - the form's in-flight guard.
 */
export function clearSettledOutcome(mutation: SettleableMutation, inFlight: InFlightGuard): void {
    if (inFlight.current || mutation.status === 'idle') {
        return;
    }
    mutation.reset();
}
