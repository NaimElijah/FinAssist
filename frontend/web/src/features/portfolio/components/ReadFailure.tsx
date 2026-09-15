import type { UseQueryResult } from '@tanstack/react-query';

import { Button, ErrorState } from '@/shared/ui';

/**
 * A failed read's error state, with a retry busy while it is in flight.
 *
 * Only for a failure a second attempt might not meet. The portfolio's two considered answers -
 * nothing held, and no usable price - are calm empty states instead, with no retry, since neither
 * recovers on a second attempt.
 *
 * @param props.query - the failed read.
 */
export function ReadFailure({ query }: { readonly query: UseQueryResult }) {
    return (
        <ErrorState
            error={query.error}
            action={
                <Button
                    variant="secondary"
                    pending={query.isFetching}
                    onClick={() => {
                        void query.refetch();
                    }}
                >
                    Try again
                </Button>
            }
        />
    );
}
