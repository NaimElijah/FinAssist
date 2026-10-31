import { useCallback, useEffect, useId, useRef, useState } from 'react';

import { useWriteAccess } from '@/shared/lib/writeAccess';
import {
    type Column,
    DataTable,
    EmptyState,
    LoadingNote,
    QueryErrorState,
    useRetryFocus,
} from '@/shared/ui';

import { useScenarios } from '../hooks/planQueries';
import type { Scenario } from '../types/scenario';
import { DefineScenarioForm } from './DefineScenarioForm';
import styles from './ScenariosPage.module.css';

const EMPTY_MESSAGE = 'No scenarios yet — the do-nothing baseline is a scenario with no flows';

/**
 * One scenario's name, taking focus once when it was just defined - the same signal
 * `AccountsPage`'s `AccountLink` gives its new row, without a link to carry it: a scenario has no
 * detail page.
 */
function NameCell({
    scenario,
    focused,
    onFocused,
}: {
    readonly scenario: Scenario;
    readonly focused: boolean;
    /** Called once focus has been applied, so the request to focus can be cleared. */
    readonly onFocused: () => void;
}) {
    const ref = useRef<HTMLSpanElement>(null);

    useEffect(() => {
        if (focused) {
            ref.current?.focus();
            onFocused();
        }
    }, [focused, onFocused]);

    return (
        <span ref={ref} tabIndex={-1}>
            {scenario.name}
        </span>
    );
}

/** The scenarios table's columns. The name heads each row. */
function scenarioColumns(
    focusedScenarioId: string | undefined,
    onFocused: () => void,
): readonly Column<Scenario>[] {
    return [
        {
            key: 'name',
            header: 'Name',
            rowHeader: true,
            render: (scenario) => (
                <NameCell
                    scenario={scenario}
                    focused={scenario.id === focusedScenarioId}
                    onFocused={onFocused}
                />
            ),
        },
        { key: 'currency', header: 'Currency', render: (scenario) => scenario.currency },
        {
            key: 'flows',
            header: 'Flows',
            align: 'end',
            // An array length, fine: a count is not money and carries no arithmetic (ADR-029).
            render: (scenario) => String(scenario.flows.length),
        },
        {
            key: 'growthRatePerStep',
            header: 'Growth per step',
            align: 'end',
            // The rate exactly as the ledger sent it, never converted to a percentage - that is
            // arithmetic on a decimal, which this client does not do (ADR-029).
            render: (scenario) => scenario.growthRatePerStep,
        },
    ];
}

/**
 * The scenarios list in whichever state its request is in.
 *
 * Scenarios already on screen stay there when a later refetch fails: TanStack Query keeps the
 * data it holds, so the failure and its retry are shown above the rows rather than in their place.
 */
function ScenariosList({
    focusedScenarioId,
    onFocused,
}: {
    readonly focusedScenarioId: string | undefined;
    readonly onFocused: () => void;
}) {
    const scenarios = useScenarios();
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(
        scenarios.error,
        scenarios.isFetching,
        () => {
            void scenarios.refetch();
        },
    );

    if (scenarios.isPending) {
        return <LoadingNote message="Loading scenarios…" />;
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
        <div ref={targetRef} tabIndex={-1} className={styles.stack}>
            {failure}
            {scenarios.data.length === 0 ? (
                <EmptyState message={EMPTY_MESSAGE} />
            ) : (
                <DataTable
                    caption="Scenarios"
                    captionHidden
                    columns={scenarioColumns(focusedScenarioId, onFocused)}
                    rows={scenarios.data}
                    rowKey={(scenario) => scenario.id}
                />
            )}
        </div>
    );
}

/**
 * `/planning/scenarios`: every scenario with its currency, flow count and growth rate per step,
 * in the ledger's order, and, for a session with write access, the form that defines another.
 *
 * Defining a scenario resets the form and moves focus to the new scenario's name in the list,
 * once - the one success signal, mirroring `AccountsPage`'s own pattern.
 *
 * A read-only session (`useWriteAccess`) sees the list alone; the form is hidden (ADR-032).
 */
export function ScenariosPage() {
    const { canWrite } = useWriteAccess();
    const listHeadingId = useId();
    const [definedScenarioId, setDefinedScenarioId] = useState<string | undefined>(undefined);
    // Stable, so the row's focus effect does not run again on every render of the page.
    const clearDefinedScenario = useCallback(() => {
        setDefinedScenarioId(undefined);
    }, []);

    return (
        <>
            <section aria-labelledby={listHeadingId} className={styles.list}>
                <h2 id={listHeadingId} className={styles.listHeading}>
                    All scenarios
                </h2>
                <ScenariosList
                    focusedScenarioId={definedScenarioId}
                    onFocused={clearDefinedScenario}
                />
            </section>
            {canWrite ? (
                <DefineScenarioForm
                    onDefined={(scenario) => {
                        setDefinedScenarioId(scenario.id);
                    }}
                />
            ) : null}
        </>
    );
}
