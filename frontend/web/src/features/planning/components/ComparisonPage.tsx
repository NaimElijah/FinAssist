import { type SubmitEvent, useId, useRef, useState } from 'react';

import {
    Button,
    type Column,
    DataTable,
    MoneyText,
    SelectField,
    type SelectOption,
} from '@/shared/ui';

import { useAccountOptions } from '../hooks/accountOptions';
import { useComparison, useInvalidateComparison } from '../hooks/planQueries';
import { offeredAccounts } from '../types/accountOption';
import type { ComparedScenario, Comparison, ComparisonParams } from '../types/comparison';
import {
    INITIAL_SETTINGS_FORM,
    type ProjectionSettingsForm,
    toProjectionSettings,
} from '../types/projectionSettings';
import type { Scenario } from '../types/scenario';
import { BALANCE_POINT_COLUMNS } from './balancePointColumns';
import groupStyles from './checkboxGroup.module.css';
import styles from './ComparisonPage.module.css';
import { ProjectionSettingsFields } from './ProjectionSettingsFields';
import { ReadResult } from './ReadResult';
import { ScenarioChoice } from './ScenarioChoice';
import { toggled } from './toggled';

/** A comparison needs something to compare against; the ledger refuses fewer with 400. */
const MINIMUM_SCENARIOS = 2;

/** The most scenarios the ledger compares in one request; it refuses more with 400. */
const MAXIMUM_SCENARIOS = 10;

/** The bounds, stated under the Scenarios legend before any submit can be refused by them. */
const SCENARIOS_HINT = `Choose ${String(MINIMUM_SCENARIOS)} to ${String(MAXIMUM_SCENARIOS)} scenarios.`;

const TOO_FEW_SCENARIOS_MESSAGE = 'Choose at least two scenarios to compare.';

const TOO_MANY_SCENARIOS_MESSAGE = 'Choose at most ten scenarios to compare.';

const NO_SCENARIOS_MESSAGE = 'No scenarios to compare yet — define at least two under Scenarios.';

const NO_SCENARIO_CHOSEN_MESSAGE = 'Choose scenarios to offer the accounts in their currency.';

const MIXED_CURRENCIES_MESSAGE =
    'The chosen scenarios are in more than one currency, and a comparison covers one.';

/**
 * Why `count` chosen scenarios cannot be compared, or `undefined` when the ledger will take them:
 * the same bounds it enforces, checked first so the owner gets a field error rather than its 400.
 */
function scenarioCountError(count: number): string | undefined {
    if (count < MINIMUM_SCENARIOS) {
        return TOO_FEW_SCENARIOS_MESSAGE;
    }
    if (count > MAXIMUM_SCENARIOS) {
        return TOO_MANY_SCENARIOS_MESSAGE;
    }
    return undefined;
}

/** Offered, disabled, while no scenario is chosen: there is nothing to measure from yet. */
const NO_BASELINE_OPTIONS: readonly SelectOption[] = [
    { value: '', label: 'Choose scenarios first' },
];

/**
 * The end-state table's columns: the scenario, marked when it is the baseline, then its amounts.
 */
function endStateColumns(baselineId: string): readonly Column<ComparedScenario>[] {
    return [
        {
            key: 'scenario',
            header: 'Scenario',
            rowHeader: true,
            render: (scenario) =>
                scenario.id === baselineId ? (
                    <>
                        {scenario.name} <span className={styles.baselineMark}>(baseline)</span>
                    </>
                ) : (
                    scenario.name
                ),
        },
        {
            key: 'cash',
            header: 'Cash',
            align: 'end',
            render: ({ endState }) => (
                <MoneyText amount={endState.cash.amount} currency={endState.cash.currency} />
            ),
        },
        {
            key: 'invested',
            header: 'Invested',
            align: 'end',
            render: ({ endState }) => (
                <MoneyText
                    amount={endState.invested.amount}
                    currency={endState.invested.currency}
                />
            ),
        },
        {
            key: 'total',
            header: 'Total',
            align: 'end',
            render: ({ endState }) => (
                <MoneyText amount={endState.total.amount} currency={endState.total.currency} />
            ),
        },
    ];
}

/**
 * Every scenario's end state, the baseline marked, then one divergence table per scenario that is
 * not the baseline. Every amount is the signed string the ledger sent (ADR-029).
 */
function ComparisonTables({ comparison }: { readonly comparison: Comparison }) {
    const { baseline } = comparison;
    return (
        <>
            <DataTable
                caption="End states"
                columns={endStateColumns(baseline.id)}
                rows={comparison.scenarios}
                rowKey={(scenario) => scenario.id}
            />
            {comparison.scenarios
                .filter((scenario) => scenario.id !== baseline.id)
                .map((scenario) => (
                    <DataTable
                        key={scenario.id}
                        caption={`${scenario.name} versus ${baseline.name}`}
                        columns={BALANCE_POINT_COLUMNS}
                        rows={scenario.divergence}
                        rowKey={(difference) => difference.date}
                    />
                ))}
        </>
    );
}

/** One comparison's result, in `ReadResult`'s section: its end-state and divergence tables. */
function ComparisonResult({ params }: { readonly params: ComparisonParams }) {
    const query = useComparison(params);
    return (
        <ReadResult
            heading="Comparison"
            pendingMessage="Comparing…"
            query={query}
            submission={params}
        >
            {(comparison) => <ComparisonTables comparison={comparison} />}
        </ReadResult>
    );
}

/** The comparison form over a loaded, non-empty scenarios list. */
function ComparisonForm({
    scenarios,
    onCompare,
}: {
    readonly scenarios: readonly Scenario[];
    readonly onCompare: (params: ComparisonParams) => void;
}) {
    const scenariosHintId = useId();
    const scenariosErrorId = useId();
    const scenariosRef = useRef<HTMLFieldSetElement>(null);
    const accountOptions = useAccountOptions();
    const [chosenIds, setChosenIds] = useState<ReadonlySet<string>>(new Set());
    const [baselineChoice, setBaselineChoice] = useState('');
    const [settings, setSettings] = useState<ProjectionSettingsForm>(INITIAL_SETTINGS_FORM);
    const [scenariosError, setScenariosError] = useState<string | undefined>(undefined);

    const chosen = scenarios.filter((scenario) => chosenIds.has(scenario.id));
    // The baseline is always one of the chosen scenarios: when the one picked is unchecked, the
    // first still chosen stands in, so the request never names a baseline it does not compare.
    const baselineId = chosen.some((scenario) => scenario.id === baselineChoice)
        ? baselineChoice
        : (chosen[0]?.id ?? '');
    const currencies = new Set(chosen.map((scenario) => scenario.currency));
    const currency = currencies.size === 1 ? chosen[0]?.currency : undefined;

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        const countError = scenarioCountError(chosen.length);
        if (countError !== undefined) {
            setScenariosError(countError);
            // The first invalid field takes focus, as in every form in the app; a checkbox group's
            // first box stands for the group.
            scenariosRef.current
                ?.querySelector<HTMLInputElement>('input[type="checkbox"]')
                ?.focus();
            return;
        }
        setScenariosError(undefined);
        onCompare({
            scenarioIds: chosen.map((scenario) => scenario.id),
            baselineId,
            ...toProjectionSettings(settings, offeredAccounts(accountOptions), currency),
        });
    }

    return (
        <form className={styles.form} onSubmit={submit}>
            <fieldset ref={scenariosRef} className={groupStyles.group}>
                <legend className={groupStyles.legend}>Scenarios</legend>
                <p id={scenariosHintId} className={styles.hint}>
                    {SCENARIOS_HINT}
                </p>
                <div className={groupStyles.options}>
                    {scenarios.map((scenario) => (
                        <label key={scenario.id} className={groupStyles.option}>
                            <input
                                type="checkbox"
                                checked={chosenIds.has(scenario.id)}
                                aria-invalid={scenariosError === undefined ? undefined : true}
                                // On each box rather than the group: focus lands on a box, so
                                // the box itself carries the rule, and the error that explains
                                // why it was refused - the rule first, as a field's hint is.
                                aria-describedby={
                                    scenariosError === undefined
                                        ? scenariosHintId
                                        : `${scenariosHintId} ${scenariosErrorId}`
                                }
                                onChange={() => {
                                    // The error answered the choice as submitted, not this one.
                                    setScenariosError(undefined);
                                    setChosenIds((previous) => toggled(previous, scenario.id));
                                }}
                            />
                            {`${scenario.name} (${scenario.currency})`}
                        </label>
                    ))}
                </div>
                {scenariosError === undefined ? null : (
                    <p id={scenariosErrorId} className={styles.error}>
                        {scenariosError}
                    </p>
                )}
            </fieldset>
            <div className={styles.fields}>
                <SelectField
                    label="Baseline"
                    options={
                        chosen.length === 0
                            ? NO_BASELINE_OPTIONS
                            : chosen.map((scenario) => ({
                                  value: scenario.id,
                                  label: scenario.name,
                              }))
                    }
                    value={baselineId}
                    disabled={chosen.length === 0}
                    onChange={(event) => {
                        setBaselineChoice(event.target.value);
                    }}
                />
            </div>
            <ProjectionSettingsFields
                value={settings}
                onChange={setSettings}
                currency={currency}
                noCurrencyMessage={
                    chosen.length === 0 ? NO_SCENARIO_CHOSEN_MESSAGE : MIXED_CURRENCIES_MESSAGE
                }
                noun="comparison"
            />
            <div>
                <Button type="submit">Compare</Button>
            </div>
        </form>
    );
}

/**
 * `/planning/compare`: choose two to ten scenarios, a baseline among them, one horizon, step and
 * set of opening accounts, and read each scenario's end state and how far it diverges from the
 * baseline at every step.
 *
 * Fewer than two scenarios, or more than ten, is refused as a field error before anything is
 * sent; the ledger stays authoritative for everything else, a currency mismatch included. The
 * accounts offered follow the chosen scenarios' one currency, and none are offered while they span
 * several.
 *
 * The result is tables alone. `ProjectionChart` draws one scenario's two lines; drawing several
 * scenarios would need a chart generalised over series, and the tables already carry every
 * figure. Like a projection, every submit reaches the ledger, unchanged fields included, and a
 * comparison is a side-effect-free read, so there is no double submit to guard against.
 */
export function ComparisonPage() {
    const headingId = useId();
    const [submitted, setSubmitted] = useState<ComparisonParams | undefined>(undefined);
    const invalidateComparison = useInvalidateComparison();

    function compare(params: ComparisonParams) {
        // Unchanged fields keep the same key, which the cache alone would answer.
        invalidateComparison(params);
        setSubmitted(params);
    }

    return (
        <>
            <section aria-labelledby={headingId} className={styles.section}>
                <h2 id={headingId} className={styles.heading}>
                    Compare scenarios
                </h2>
                <ScenarioChoice emptyMessage={NO_SCENARIOS_MESSAGE}>
                    {(scenarios) => <ComparisonForm scenarios={scenarios} onCompare={compare} />}
                </ScenarioChoice>
            </section>
            {submitted === undefined ? null : <ComparisonResult params={submitted} />}
        </>
    );
}
