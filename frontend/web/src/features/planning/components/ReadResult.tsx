import type { UseQueryResult } from '@tanstack/react-query';
import { type ReactNode, useEffect, useId, useRef } from 'react';

import { QueryErrorState, useRetryFocus } from '@/shared/ui';

import styles from './ReadResult.module.css';

/** A read's result: what its section is called, what it says while waiting, and its answer. */
export interface ReadResultProps<Data> {
    /** The section's heading, such as "Projected balances". */
    readonly heading: string;
    /** Shown until the first answer arrives, such as "Projecting…". */
    readonly pendingMessage: string;
    /** The read whose state the section shows. */
    readonly query: UseQueryResult<Data>;
    /** Renders the answer; it stays on screen, below the failure, when a later read fails. */
    readonly children: (data: Data) => ReactNode;
}

/**
 * The result of a read the owner asked for by submitting a form - a projection or a comparison: a
 * headed section showing the request pending, its failure, and its answer.
 *
 * A failure takes focus, the rule every form in the app keeps for a submission nothing on a field
 * explains, and every new failure takes it again. A retry is offered only where a second attempt
 * could change the answer - a network failure, or a 5xx other than a 503 - so a 422 for a horizon
 * that is not whole steps is explained and offers nothing to press. A retry that succeeds moves
 * focus to the section's heading, since the control it was pressed on goes with the failure.
 *
 * @param props - see `ReadResultProps`.
 */
export function ReadResult<Data>({
    heading,
    pendingMessage,
    query,
    children,
}: ReadResultProps<Data>) {
    const headingId = useId();
    const failureRef = useRef<HTMLDivElement>(null);
    const failure = query.error;
    const { targetRef, onRetry } = useRetryFocus<HTMLHeadingElement>(
        failure,
        query.isFetching,
        () => {
            void query.refetch();
        },
    );

    // Runs after the render that shows the failure, so the error state is already on the page;
    // each failed read is a new error object, so each one moves focus.
    useEffect(() => {
        if (failure !== null) {
            failureRef.current?.focus();
        }
    }, [failure]);

    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 ref={targetRef} id={headingId} tabIndex={-1} className={styles.heading}>
                {heading}
            </h2>
            {query.isPending ? <p className={styles.pending}>{pendingMessage}</p> : null}
            <QueryErrorState
                ref={failureRef}
                error={failure}
                retrying={query.isFetching}
                onRetry={onRetry}
            />
            {query.data === undefined ? null : children(query.data)}
        </section>
    );
}
