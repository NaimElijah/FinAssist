import { Button } from './Button';

/** Whether a retry is in flight, and how to start one. */
export interface RetryButtonProps {
    /** A request for the failed read is in flight; the button is disabled until it settles. */
    readonly retrying: boolean;
    /** Sends the failed read again. */
    readonly onRetry: () => void;
}

/**
 * The "Try again" control a failed read offers: a secondary button, disabled and `aria-busy` while
 * the retry is in flight.
 *
 * It takes the retry as a callback rather than a query, so `shared` names no data-fetching library,
 * and a read that is not a screen's own query - such as the account choices `src/app` gives
 * planning - offers the same control.
 *
 * @param props - see `RetryButtonProps`.
 */
export function RetryButton({ retrying, onRetry }: RetryButtonProps) {
    return (
        <Button
            variant="secondary"
            pending={retrying}
            onClick={() => {
                onRetry();
            }}
        >
            Try again
        </Button>
    );
}
