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
 * Clear what a settled mutation answered - its error, or its result - when the owner edits the form
 * that sent it, so a screen never shows an answer beside inputs it no longer answers.
 *
 * A mutation still in flight is left alone. Resetting one detaches the form from it, so the
 * callbacks its `mutate` was given would never run - among them the `onSettled` that releases a
 * form's in-flight guard - and the form would stay locked.
 *
 * @param mutation - the form's mutation.
 */
export function clearSettledOutcome(mutation: SettleableMutation): void {
    if (mutation.status === 'error' || mutation.status === 'success') {
        mutation.reset();
    }
}
