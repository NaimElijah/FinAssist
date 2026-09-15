import type { Ref } from 'react';

import { isRetryable } from '../api/retry';
import { ErrorState } from './ErrorState';
import { RetryButton } from './RetryButton';

/** A read's failure, and how to send the read again. */
export interface QueryErrorStateProps {
    /** What the read failed with, or `null` while it has not failed. */
    readonly error: unknown;
    /** A request for the read is in flight. */
    readonly retrying: boolean;
    /** Sends the read again. */
    readonly onRetry: () => void;
    /** The alert's element, for a screen that moves focus to the failure; see `ErrorState`. */
    readonly ref?: Ref<HTMLDivElement>;
}

/**
 * A failed read's error state, or nothing while the read has not failed.
 *
 * A retry is offered only when `isRetryable` says a second attempt could change the answer: a
 * network failure, or a 5xx other than a 503. A 4xx is the server's considered answer and a defect
 * would only repeat itself, so for either the alert explains the failure with nothing to press.
 *
 * A screen renders it beside data the read still holds, never in its place: TanStack Query keeps a
 * query's data when a later refetch fails, so what the owner was reading stays on screen below the
 * failure. It takes plain values and a callback, so `shared` names no data-fetching library.
 *
 * @param props - see `QueryErrorStateProps`.
 */
export function QueryErrorState({ error, retrying, onRetry, ref }: QueryErrorStateProps) {
    if (error === null) {
        return null;
    }
    return (
        <ErrorState
            ref={ref}
            error={error}
            action={
                isRetryable(error) ? (
                    <RetryButton retrying={retrying} onRetry={onRetry} />
                ) : undefined
            }
        />
    );
}
