import type { ReactNode } from 'react';

import { EmptyState, QueryErrorState } from '@/shared/ui';

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
 * state their request is in: loading, failed with a retry, none defined, or loaded.
 *
 * A failed background refetch never hides a form already on screen: TanStack Query keeps the list
 * it holds, so the failure is shown above the form rather than in its place.
 *
 * @param props - see `ScenarioChoiceProps`.
 */
export function ScenarioChoice({ emptyMessage, children }: ScenarioChoiceProps) {
    const scenarios = useScenarios();

    if (scenarios.isPending) {
        return <p className={styles.loading}>Loading scenarios…</p>;
    }
    const failure = (
        <QueryErrorState
            error={scenarios.error}
            retrying={scenarios.isFetching}
            onRetry={() => {
                void scenarios.refetch();
            }}
        />
    );
    if (scenarios.data === undefined) {
        return failure;
    }
    if (scenarios.data.length === 0) {
        return <EmptyState message={emptyMessage} />;
    }
    return (
        <>
            {failure}
            {children(scenarios.data)}
        </>
    );
}
