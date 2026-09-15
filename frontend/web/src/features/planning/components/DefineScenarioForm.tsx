import { type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import {
    Button,
    ErrorState,
    MoneyText,
    SelectField,
    type SelectOption,
    TextField,
} from '@/shared/ui';

import { useDefineScenario, usePlannedFlows } from '../hooks/planQueries';
import { DIRECTION_LABELS, PLAN_STATUS_LABELS, type PlannedFlow } from '../types/flow';
import { CURRENCIES, type Currency } from '../types/money';
import type { Scenario } from '../types/scenario';
import styles from './DefineScenarioForm.module.css';
import { QueryFailure } from './QueryFailure';

const CURRENCY_OPTIONS: readonly SelectOption[] = CURRENCIES.map((currency) => ({
    value: currency,
    label: currency,
}));

/** A new scenario starts in shekels, the currency the owner keeps books in (see `OpenAccountForm`). */
const DEFAULT_CURRENCY: Currency = 'ILS';

/** The rate a do-nothing scenario carries: no growth at all. */
const DEFAULT_GROWTH_RATE = '0';

/** ADR-028 lists field `errors` only on a 400. */
const MALFORMED_REQUEST_STATUS = 400;

/**
 * Matches a control showing an error: the shared field frame marks it `aria-invalid` exactly while
 * it has one, and describes it by that error.
 */
const INVALID_CONTROL_SELECTOR = '[aria-invalid="true"]';

/**
 * The form's top-level fields, named as the ledger names them in a problem's `errors`. `flows` is
 * not among them - a checkbox group has no single field frame to carry an error - so a 400 naming
 * it falls to `ErrorState` instead of being silently dropped, the same exclusion
 * `RecordTransactionForm` makes for an entry row's fields.
 */
const FIELD_NAMES = ['name', 'currency', 'growthRatePerStep'] as const;

type FieldName = (typeof FIELD_NAMES)[number];

type FieldErrors = Partial<Record<FieldName, string>>;

/** A failed submission, split into what can be shown on a field and what cannot. */
interface SubmissionErrors {
    readonly fields: FieldErrors;
    /** The failure itself when no field explains it, for `ErrorState`; otherwise `null`. */
    readonly unplaced: Error | null;
}

function isFieldName(field: string): field is FieldName {
    return FIELD_NAMES.some((name) => name === field);
}

/**
 * `value` as one of `allowed`.
 *
 * @throws Error - when it is not one, which a select offering only `allowed` cannot produce.
 */
function oneOf<Value extends string>(allowed: readonly Value[], value: string): Value {
    const match = allowed.find((candidate) => candidate === value);
    if (match === undefined) {
        throw new Error('A select produced a value it does not offer');
    }
    return match;
}

/** Between two messages for the same field; see `OpenAccountForm`. */
const FIELD_MESSAGE_SEPARATOR = '; ';

/**
 * A 400's field errors keyed by this form's top-level fields, or `undefined` when any names a
 * field this form does not have at top level (such as `flows`), so the whole problem is shown
 * instead of part of it being dropped silently.
 */
function placeFieldErrors(errors: readonly ProblemFieldError[]): FieldErrors | undefined {
    const fields: FieldErrors = {};
    for (const { field, message } of errors) {
        if (!isFieldName(field)) {
            return undefined;
        }
        const earlier = fields[field];
        fields[field] =
            earlier === undefined ? message : `${earlier}${FIELD_MESSAGE_SEPARATOR}${message}`;
    }
    return fields;
}

/**
 * Where a failed submission's explanation belongs. A 400 whose `errors` all name a field this
 * form has at top level goes on those fields; anything else - a 422 `UNKNOWN_PLAN` or
 * `RULE_VIOLATED`, or an `errors` entry naming `flows` - is left for `ErrorState`, which shows
 * the problem's detail.
 */
function submissionErrors(error: Error | null): SubmissionErrors {
    if (error instanceof ApiError && error.problem !== undefined) {
        const { problem } = error;
        if (
            error.status === MALFORMED_REQUEST_STATUS &&
            problem.errors !== undefined &&
            problem.errors.length > 0
        ) {
            const fields = placeFieldErrors(problem.errors);
            if (fields !== undefined) {
                return { fields, unplaced: null };
            }
        }
    }
    return { fields: {}, unplaced: error };
}

/** One flow checkbox's label: its description, amount, direction and state (requirements § Planning). */
function FlowOptionLabel({ flow }: { readonly flow: PlannedFlow }) {
    return (
        <span>
            {flow.description} —{' '}
            <MoneyText amount={flow.amount.amount} currency={flow.amount.currency} />,{' '}
            {DIRECTION_LABELS[flow.direction]}, {PLAN_STATUS_LABELS[flow.state.status]}
        </span>
    );
}

/**
 * The flows checkbox group, filtered to `currency`: every planned flow in that currency, whatever
 * its state - a cancelled flow is still offered, since the ledger decides what counts towards a
 * projection, not this form.
 */
function FlowsFieldset({
    flows,
    currency,
    selectedFlowIds,
    onToggle,
}: {
    readonly flows: readonly PlannedFlow[];
    readonly currency: Currency;
    readonly selectedFlowIds: ReadonlySet<string>;
    readonly onToggle: (flowId: string) => void;
}) {
    const flowsInCurrency = flows.filter((flow) => flow.amount.currency === currency);
    return (
        <fieldset className={styles.flowsFieldset}>
            <legend className={styles.flowsLegend}>Flows</legend>
            {flowsInCurrency.length === 0 ? (
                <p className={styles.noFlows}>{`No planned flows in ${currency} yet.`}</p>
            ) : (
                <div className={styles.flowsList}>
                    {flowsInCurrency.map((flow) => (
                        <label key={flow.id} className={styles.flowOption}>
                            <input
                                type="checkbox"
                                checked={selectedFlowIds.has(flow.id)}
                                onChange={() => {
                                    onToggle(flow.id);
                                }}
                            />
                            <FlowOptionLabel flow={flow} />
                        </label>
                    ))}
                </div>
            )}
        </fieldset>
    );
}

/** What happens once a scenario is defined. */
export interface DefineScenarioFormProps {
    /** Called with the defined scenario, once the scenarios list has been refetched to include it. */
    readonly onDefined: (scenario: Scenario) => void;
}

/**
 * The form that defines a scenario: a name, a currency, the planned flows it assumes - filtered to
 * that currency, since a scenario admits none in another (ADR-012) - and a growth rate per
 * projection step.
 *
 * Selecting no flow is legal: the do-nothing baseline is a scenario with no flows. Changing
 * currency clears the selection, since every flow it held was necessarily in the currency just
 * left - a client nicety; the server stays authoritative regardless.
 *
 * Submit is disabled while the request is pending, and a submit event arriving anyway is ignored
 * (ADR-028: no double submit). On success the form resets and `onDefined` is called. A failure is
 * shown where it can be acted on: a 400's top-level field errors on their fields, and anything
 * else - a 422 `UNKNOWN_PLAN` or `RULE_VIOLATED` - in an `ErrorState` above the form. Focus
 * follows the failure there, the rule every form in the app keeps.
 *
 * @param props - see `DefineScenarioFormProps`.
 */
export function DefineScenarioForm({ onDefined }: DefineScenarioFormProps) {
    const headingId = useId();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    /** Set from the moment a submit starts a request until that request settles. */
    const submitting = useRef(false);

    const [name, setName] = useState('');
    const [currency, setCurrency] = useState<Currency>(DEFAULT_CURRENCY);
    const [selectedFlowIds, setSelectedFlowIds] = useState<ReadonlySet<string>>(new Set());
    const [growthRatePerStep, setGrowthRatePerStep] = useState(DEFAULT_GROWTH_RATE);

    const flowsQuery = usePlannedFlows();
    const defineScenario = useDefineScenario();
    const failure = defineScenario.error;
    const errors = submissionErrors(failure);

    // Runs after the render that shows the failure, so the invalid controls and the error state
    // are already on the page; each failure is a new error object, so each one moves focus.
    useEffect(() => {
        if (failure === null) {
            return;
        }
        const invalidControl =
            formRef.current?.querySelector<HTMLElement>(INVALID_CONTROL_SELECTOR);
        (invalidControl ?? errorStateRef.current)?.focus();
    }, [failure]);

    function toggleFlow(flowId: string) {
        setSelectedFlowIds((previous) => {
            const next = new Set(previous);
            if (next.has(flowId)) {
                next.delete(flowId);
            } else {
                next.add(flowId);
            }
            return next;
        });
    }

    function changeCurrency(nextCurrency: Currency) {
        setCurrency(nextCurrency);
        // Every currently selected flow was offered under the currency just left, and a scenario
        // admits no flow in another currency, so the whole selection is cleared.
        setSelectedFlowIds(new Set());
    }

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // The disabled button stops a click, not Enter in a field or a scripted submit, and a
        // second define is a second scenario.
        if (submitting.current) {
            return;
        }
        const flows = (flowsQuery.data ?? [])
            .filter((flow) => selectedFlowIds.has(flow.id))
            .map((flow) => flow.id);

        submitting.current = true;
        defineScenario.mutate(
            { name, currency, flows, growthRatePerStep },
            {
                onSuccess: (scenario) => {
                    setName('');
                    setCurrency(DEFAULT_CURRENCY);
                    setSelectedFlowIds(new Set());
                    setGrowthRatePerStep(DEFAULT_GROWTH_RATE);
                    onDefined(scenario);
                },
                onSettled: () => {
                    submitting.current = false;
                },
            },
        );
    }

    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                Define a scenario
            </h2>
            {errors.unplaced === null ? null : (
                <ErrorState ref={errorStateRef} error={errors.unplaced} />
            )}
            <form ref={formRef} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <TextField
                        label="Name"
                        required
                        autoComplete="off"
                        value={name}
                        error={errors.fields.name}
                        onChange={(event) => {
                            setName(event.target.value);
                        }}
                    />
                    <SelectField
                        label="Currency"
                        options={CURRENCY_OPTIONS}
                        value={currency}
                        error={errors.fields.currency}
                        onChange={(event) => {
                            changeCurrency(oneOf(CURRENCIES, event.target.value));
                        }}
                    />
                    <TextField
                        label="Growth rate per step"
                        inputMode="decimal"
                        required
                        autoComplete="off"
                        hint="e.g. 0.005 per step"
                        value={growthRatePerStep}
                        error={errors.fields.growthRatePerStep}
                        onChange={(event) => {
                            setGrowthRatePerStep(event.target.value);
                        }}
                    />
                </div>
                {flowsQuery.isPending ? (
                    <p>Loading planned flows…</p>
                ) : flowsQuery.data === undefined ? (
                    <QueryFailure query={flowsQuery} />
                ) : (
                    <FlowsFieldset
                        flows={flowsQuery.data}
                        currency={currency}
                        selectedFlowIds={selectedFlowIds}
                        onToggle={toggleFlow}
                    />
                )}
                <div>
                    <Button type="submit" pending={defineScenario.isPending}>
                        Define scenario
                    </Button>
                </div>
            </form>
        </section>
    );
}
