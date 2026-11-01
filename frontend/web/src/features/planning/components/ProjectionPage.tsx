import { type SubmitEvent, useId, useState } from 'react';

import { useWriteAccess } from '@/shared/lib/writeAccess';
import { Button, DataTable, DateText, SelectField, type SelectOption } from '@/shared/ui';

import { useAccountOptions } from '../hooks/accountOptions';
import { useInvalidateProjection, useProjection } from '../hooks/planQueries';
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
import { ReadResult } from './ReadResult';
import { ScenarioChoice } from './ScenarioChoice';

const NO_SCENARIOS_MESSAGE = 'No scenarios to project yet — define one under Scenarios.';

/** No scenarios, for a viewer, whom Scenarios offers no form to define one with (ADR-032). */
const READ_ONLY_NO_SCENARIOS_MESSAGE = 'No scenarios to project yet.';

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
 * One projection's result, in `ReadResult`'s section: the callout, the chart, and the table every
 * figure on the chart can be read from.
 */
function ProjectionResult({ params }: { readonly params: ProjectionParams }) {
    const query = useProjection(params);
    return (
        <ReadResult
            heading="Projected balances"
            pendingMessage="Projecting…"
            query={query}
            submission={params}
        >
            {(projection) => (
                <>
                    <ShortfallCallout projection={projection} />
                    <ProjectionChart projection={projection} />
                    <DataTable
                        caption="Projection"
                        columns={BALANCE_POINT_COLUMNS}
                        rows={projection.points}
                        rowKey={(point) => point.date}
                    />
                </>
            )}
        </ReadResult>
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
    const { canWrite } = useWriteAccess();

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
                <ScenarioChoice
                    emptyMessage={canWrite ? NO_SCENARIOS_MESSAGE : READ_ONLY_NO_SCENARIOS_MESSAGE}
                >
                    {(scenarios) => <ProjectionForm scenarios={scenarios} onProject={project} />}
                </ScenarioChoice>
            </section>
            {submitted === undefined ? null : <ProjectionResult params={submitted} />}
        </>
    );
}
