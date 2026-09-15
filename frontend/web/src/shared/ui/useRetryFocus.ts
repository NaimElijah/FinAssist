import { type RefObject, useEffect, useRef } from 'react';

/** Where focus goes once a retry succeeds, and the retry that remembers the owner asked for it. */
export interface RetryFocus<Target extends HTMLElement> {
    /**
     * For the element a successful retry moves focus to: the content the retry restored, or the
     * heading naming it. It needs `tabIndex={-1}` unless it takes focus natively.
     */
    readonly targetRef: RefObject<Target | null>;
    /** Sends the read again, remembering that the owner asked: `QueryErrorState`'s `onRetry`. */
    readonly onRetry: () => void;
}

/**
 * Move focus to the content a successful retry restores, when the retry has left focus nowhere.
 *
 * A retry control is disabled while its request runs, which drops focus, and a success removes the
 * error state it sat in, so focus would otherwise be left on the page body: a keyboard user starts
 * again from the top, and a screen reader announces nothing of what came back.
 *
 * Only a retry the owner asked for moves focus, and only once the read has settled without an
 * error, so a background refetch that happens to succeed never takes it, and a retry that fails
 * again leaves focus to the failure. Focus is moved only while it rests on the body: an element
 * that has already taken it - such as a list's newly opened row - holds the more specific answer.
 *
 * Call it in the component that stays mounted across the retry, the same one that renders the
 * error state and the content, since what it remembers must outlast both. Destructure what it
 * returns: the React Compiler's lint reads any member of an object holding a ref, `onRetry`
 * included, as reading that ref during render.
 *
 * @param error - what the read failed with, or `null` while it has not failed.
 * @param retrying - a request for the read is in flight, or it is loading for the first time.
 * @param retry - sends the read again.
 * @returns see `RetryFocus`.
 */
export function useRetryFocus<Target extends HTMLElement>(
    error: unknown,
    retrying: boolean,
    retry: () => void,
): RetryFocus<Target> {
    const targetRef = useRef<Target>(null);
    const retryRequested = useRef(false);

    // Runs after the render that settles the retry, so the restored content is already on the page.
    useEffect(() => {
        if (!retryRequested.current || retrying) {
            return;
        }
        retryRequested.current = false;
        const focusLost =
            document.activeElement === null || document.activeElement === document.body;
        if (error === null && focusLost) {
            targetRef.current?.focus();
        }
    }, [error, retrying]);

    return {
        targetRef,
        onRetry: () => {
            retryRequested.current = true;
            retry();
        },
    };
}
