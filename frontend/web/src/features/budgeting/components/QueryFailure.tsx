import type { UseQueryResult } from '@tanstack/react-query';

import { Button, ErrorState } from '@/shared/ui';

/** A retry for a failed query, busy while the retry is in flight. */
function RetryButton({
    query,
}: {
    readonly query: Pick<UseQueryResult, 'isFetching' | 'refetch'>;
}) {
    return (
        <Button
            variant="secondary"
            pending={query.isFetching}
            onClick={() => {
                void query.refetch();
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
 * budgeting list screen - `BudgetsPage`, `BudgetReviewPage` and `CategorisationRulesPage` - so
 * the retry button and its pending state read the same way everywhere.
 */
export function QueryFailure({ query }: { readonly query: UseQueryResult }) {
    return query.isError ? (
        <ErrorState error={query.error} action={<RetryButton query={query} />} />
    ) : null;
}
