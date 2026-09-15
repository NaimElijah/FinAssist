import type { UseQueryResult } from '@tanstack/react-query';
import { type ReactNode, useEffect, useId, useRef } from 'react';

import { ErrorState } from '@/shared/ui';

import { RetryButton } from './QueryFailure';
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
 * explains, and every new failure takes it again. A retry is offered, since a network failure or a
 * 5xx may pass.
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

    // Runs after the render that shows the failure, so the error state is already on the page;
    // each failed read is a new error object, so each one moves focus.
    useEffect(() => {
        if (failure !== null) {
            failureRef.current?.focus();
        }
    }, [failure]);

    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                {heading}
            </h2>
            {query.isPending ? <p className={styles.pending}>{pendingMessage}</p> : null}
            {failure === null ? null : (
                <ErrorState
                    ref={failureRef}
                    error={failure}
                    action={
                        <RetryButton
                            retrying={query.isFetching}
                            onRetry={() => {
                                void query.refetch();
                            }}
                        />
                    }
                />
            )}
            {query.data === undefined ? null : children(query.data)}
        </section>
    );
}
