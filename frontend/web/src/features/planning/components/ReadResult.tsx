import type { UseQueryResult } from '@tanstack/react-query';
import { type ReactNode, useEffect, useId, useRef } from 'react';

import { LoadingNote, QueryErrorState, useRetryFocus } from '@/shared/ui';

import styles from './ReadResult.module.css';

/** A read's result: what its section is called, what it says while waiting, and its answer. */
export interface ReadResultProps<Data> {
    /** The section's heading, such as "Projected balances". */
    readonly heading: string;
    /** The `LoadingNote` shown until the first answer arrives, such as "Projecting…". */
    readonly pendingMessage: string;
    /** The read whose state the section shows. */
    readonly query: UseQueryResult<Data>;
    /**
     * The submit this result answers, compared by identity: each submit must pass a new object,
     * unchanged fields included, such as the parameters the form built for it.
     */
    readonly submission: object;
    /** Renders the answer; it stays on screen, below the failure, when a later read fails. */
    readonly children: (data: Data) => ReactNode;
}

/**
 * The result of a read the owner asked for by submitting a form - a projection or a comparison: a
 * headed section showing the request pending, its failure, and its answer.
 *
 * A submit's answer takes focus on its heading once the read settles without an error. The
 * submit control keeps focus and the answer lands below the form, or refreshes in place, so
 * otherwise a screen reader announces nothing of it. Only a submit's own settle moves focus - a
 * background refetch never does - and only while focus rests where the submit left it, or on the
 * body: a field the owner moved to while waiting holds the more specific intent.
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
    submission,
    children,
}: ReadResultProps<Data>) {
    const headingId = useId();
    const failureRef = useRef<HTMLDivElement>(null);
    const submitOrigin = useRef<Element | null>(null);
    const answeredSubmission = useRef<object | null>(null);
    const failure = query.error;
    // Not `!isFetching`: a read waiting on the network (offline, or a retry held on a hidden tab)
    // is `paused`, which `isFetching` reports as false although nothing has answered yet.
    const settled = query.fetchStatus === 'idle';
    const { targetRef, onRetry } = useRetryFocus<HTMLHeadingElement>(
        failure,
        query.isFetching,
        () => {
            void query.refetch();
        },
    );

    // Declared before the effect below, so in the commit carrying a new submission it runs first.
    useEffect(() => {
        submitOrigin.current = document.activeElement;
    }, [submission]);

    // The commit carrying a new submission already shows its read unsettled: the page invalidates
    // the key before setting it, and a key not yet fetched is fetching, or paused offline, from its
    // first render. So a settled read here is the submit's own answer, never the one on screen.
    useEffect(() => {
        if (answeredSubmission.current === submission || !settled) {
            return;
        }
        answeredSubmission.current = submission;
        const focused = document.activeElement;
        const focusUnmoved =
            focused === null || focused === document.body || focused === submitOrigin.current;
        if (failure === null && focusUnmoved) {
            targetRef.current?.focus();
        }
    }, [submission, settled, failure, targetRef]);

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
            {query.isPending ? <LoadingNote message={pendingMessage} /> : null}
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
