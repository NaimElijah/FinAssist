import type { ReactNode } from 'react';

import { EmptyState, QueryErrorState, useRetryFocus } from '@/shared/ui';

import { useScenarios } from '../hooks/planQueries';
import type { Scenario } from '../types/scenario';
import styles from './ScenarioChoice.module.css';

/** What to say while no scenario is defined, and the form to show over them once they load. */
export interface ScenarioChoiceProps {
    /** Shown in place of the form while no scenario is defined. */
    readonly emptyMessage: string;
    /** Renders the form over the loaded, non-empty scenarios list, in the ledger's order. */
    readonly children: (scenarios: readonly Scenario[]) => ReactNode;
}

/**
 * The scenarios a planning form chooses from - to project one, or compare several - in whichever
 * state their request is in: loading, failed, none defined, or loaded.
 *
 * A failed background refetch never hides what is already on screen: TanStack Query keeps the list
 * it holds, so the failure is shown above the form, or above the empty state when the list held
 * none, rather than in its place. A retry that restores the list moves focus to what it restored.
 *
 * @param props - see `ScenarioChoiceProps`.
 */
export function ScenarioChoice({ emptyMessage, children }: ScenarioChoiceProps) {
    const scenarios = useScenarios();
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(
        scenarios.error,
        scenarios.isFetching,
        () => {
            void scenarios.refetch();
        },
    );

    if (scenarios.isPending) {
        return <p className={styles.loading}>Loading scenarios…</p>;
    }
    const failure = (
        <QueryErrorState
            error={scenarios.error}
            retrying={scenarios.isFetching}
            onRetry={onRetry}
        />
    );
    if (scenarios.data === undefined) {
        return failure;
    }
    return (
        <div ref={targetRef} tabIndex={-1} className={styles.choice}>
            {failure}
            {scenarios.data.length === 0 ? (
                <EmptyState message={emptyMessage} />
            ) : (
                children(scenarios.data)
            )}
        </div>
    );
}
