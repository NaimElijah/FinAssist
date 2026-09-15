import type { UseQueryResult } from '@tanstack/react-query';

import { Button, ErrorState } from '@/shared/ui';

/**
 * A retry for a failed read, busy while the retry is in flight.
 *
 * Takes the retry as a plain callback, so a read that is not a query of this feature's own - the
 * account choices `src/app` provides - offers the same button.
 */
export function RetryButton({
    retrying,
    onRetry,
}: {
    readonly retrying: boolean;
    readonly onRetry: () => void;
}) {
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

/**
 * A failed read's error state with a retry, or nothing when the read has not failed.
 *
 * Shown beside data the query still holds: TanStack Query keeps a query's data when a later
 * refetch fails, so the failure never replaces what the owner was reading. Shared by every
 * planning screen that reads a list, so the retry button and its pending state read the same way
 * everywhere.
 */
export function QueryFailure({ query }: { readonly query: UseQueryResult }) {
    return query.isError ? (
        <ErrorState
            error={query.error}
            action={
                <RetryButton
                    retrying={query.isFetching}
                    onRetry={() => {
                        void query.refetch();
                    }}
                />
            }
        />
    ) : null;
}
