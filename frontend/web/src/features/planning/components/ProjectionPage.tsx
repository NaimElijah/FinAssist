import { type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import {
    Button,
    DataTable,
    DateText,
    EmptyState,
    ErrorState,
    SelectField,
    type SelectOption,
} from '@/shared/ui';

import { useAccountOptions } from '../hooks/accountOptions';
import { useInvalidateProjection, useProjection, useScenarios } from '../hooks/planQueries';
import { offeredAccounts } from '../types/accountOption';
import type { Projection, ProjectionParams } from '../types/projection';
import {
    INITIAL_SETTINGS_FORM,
    type ProjectionSettingsForm,
    toProjectionSettings,
} from '../types/projectionSettings';
import { CADENCE_LABELS } from '../types/recurrence';
import type { Scenario } from '../types/scenario';
import { BALANCE_POINT_COLUMNS } from './balancePointColumns';
import { ProjectionChart } from './ProjectionChart';
import styles from './ProjectionPage.module.css';
import { ProjectionSettingsFields } from './ProjectionSettingsFields';
import { QueryFailure, RetryButton } from './QueryFailure';

const NO_SCENARIOS_MESSAGE = 'No scenarios to project yet — define one under Scenarios.';

const NO_CURRENCY_MESSAGE = 'Choose a scenario to offer the accounts in its currency.';

/** The within-step caveat requirements § Planning states for every first-shortfall answer. */
const SHORTFALL_CAVEAT =
    'A dip that opens and closes inside a single step is not shown. Projecting at a finer cadence narrows that window; nothing closes it.';

/** The empty choice a natively required select refuses to submit. */
const NO_SCENARIO_OPTION: SelectOption = { value: '', label: 'Choose a scenario' };

/** A scenario as its option: its name, and the currency a projection of it is in. */
function scenarioOption(scenario: Scenario): SelectOption {
    return { value: scenario.id, label: `${scenario.name} (${scenario.currency})` };
}

/** Whether cash ever falls below zero, and the caveat that answer always carries. */
function ShortfallCallout({ projection }: { readonly projection: Projection }) {
    return (
        <div
            className={styles.callout}
            data-shortfall={projection.firstShortfall === undefined ? 'false' : 'true'}
        >
            {projection.firstShortfall === undefined ? (
                <p className={styles.calloutLead}>
                    {`Cash never falls below zero at a ${CADENCE_LABELS[projection.step].toLowerCase()} cadence.`}
                </p>
            ) : (
                <p className={styles.calloutLead}>
                    Cash first falls below zero at the step ending{' '}
                    <DateText value={projection.firstShortfall} />.
                </p>
            )}
            <p className={styles.caveat}>{SHORTFALL_CAVEAT}</p>
        </div>
    );
}

/**
 * One projection's result: its request's pending state or failure, then the callout, the chart,
 * and the table every figure on the chart can be read from.
 *
 * A failure takes focus, the rule every form in the app keeps for a submission nothing on a field
 * explains; a retry is offered, since a network failure or a 5xx may pass.
 */
function ProjectionResult({ params }: { readonly params: ProjectionParams }) {
    const headingId = useId();
    const failureRef = useRef<HTMLDivElement>(null);
    const projection = useProjection(params);
    const failure = projection.error;

    // Runs after the render that shows the failure, so the error state is already on the page.
    useEffect(() => {
        if (failure !== null) {
            failureRef.current?.focus();
        }
    }, [failure]);

    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                Projected balances
            </h2>
            {projection.isPending ? <p className={styles.muted}>Projecting…</p> : null}
            {failure === null ? null : (
                <ErrorState
                    ref={failureRef}
                    error={failure}
                    action={
                        <RetryButton
                            retrying={projection.isFetching}
                            onRetry={() => {
                                void projection.refetch();
                            }}
                        />
                    }
                />
            )}
            {projection.data === undefined ? null : (
                <>
                    <ShortfallCallout projection={projection.data} />
                    <ProjectionChart projection={projection.data} />
                    <DataTable
                        caption="Projection"
                        columns={BALANCE_POINT_COLUMNS}
                        rows={projection.data.points}
                        rowKey={(point) => point.date}
                    />
                </>
            )}
        </section>
    );
}

/** The projection form over a loaded, non-empty scenarios list. */
function ProjectionForm({
    scenarios,
    onProject,
}: {
    readonly scenarios: readonly Scenario[];
    readonly onProject: (params: ProjectionParams) => void;
}) {
    const accountOptions = useAccountOptions();
    const [scenarioId, setScenarioId] = useState('');
    const [settings, setSettings] = useState<ProjectionSettingsForm>(INITIAL_SETTINGS_FORM);
    const currency = scenarios.find((scenario) => scenario.id === scenarioId)?.currency;

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // Native `required` on Scenario, From and To refuses an empty one before this runs.
        onProject({
            scenarioId,
            ...toProjectionSettings(settings, offeredAccounts(accountOptions), currency),
        });
    }

    return (
        <form className={styles.form} onSubmit={submit}>
            <div className={styles.fields}>
                <SelectField
                    label="Scenario"
                    required
                    options={[NO_SCENARIO_OPTION, ...scenarios.map(scenarioOption)]}
                    value={scenarioId}
                    onChange={(event) => {
                        setScenarioId(event.target.value);
                    }}
                />
            </div>
            <ProjectionSettingsFields
                value={settings}
                onChange={setSettings}
                currency={currency}
                noCurrencyMessage={NO_CURRENCY_MESSAGE}
                noun="projection"
            />
            <div>
                <Button type="submit">Project</Button>
            </div>
        </form>
    );
}

/** The scenarios the form chooses from, in whichever state their request is in. */
function ScenarioChoice({ onProject }: { readonly onProject: (params: ProjectionParams) => void }) {
    const scenarios = useScenarios();

    if (scenarios.isPending) {
        return <p className={styles.muted}>Loading scenarios…</p>;
    }
    const failure = <QueryFailure query={scenarios} />;
    if (scenarios.data === undefined) {
        return failure;
    }
    if (scenarios.data.length === 0) {
        return <EmptyState message={NO_SCENARIOS_MESSAGE} />;
    }
    return (
        <>
            {failure}
            <ProjectionForm scenarios={scenarios.data} onProject={onProject} />
        </>
    );
}

/**
 * `/planning/projection`: choose a scenario, a horizon, a step and the accounts opening its cash
 * and invested lines, and read its projection - whether cash ever falls below zero, a chart, and
 * a table of every step.
 *
 * Submitting sets the parameters a projection query is keyed by; the result reads them until the
 * form is submitted again, so editing a field never refetches on its own. Every submit reaches the
 * ledger, unchanged fields included: planning does not follow accounting's changes, so submitting
 * again is how the owner sees what a transaction recorded since has done. A projection is a
 * side-effect-free read, so there is nothing to guard against a double submit.
 */
export function ProjectionPage() {
    const headingId = useId();
    const [submitted, setSubmitted] = useState<ProjectionParams | undefined>(undefined);
    const invalidateProjection = useInvalidateProjection();

    function project(params: ProjectionParams) {
        // Unchanged fields keep the same key, which the cache alone would answer.
        invalidateProjection(params);
        setSubmitted(params);
    }

    return (
        <>
            <section aria-labelledby={headingId} className={styles.section}>
                <h2 id={headingId} className={styles.heading}>
                    Project a scenario
                </h2>
                <ScenarioChoice onProject={project} />
            </section>
            {submitted === undefined ? null : <ProjectionResult params={submitted} />}
        </>
    );
}
