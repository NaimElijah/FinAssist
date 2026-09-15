import { type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import { todayIsoDate } from '@/shared/lib/today';
import {
    Button,
    DateField,
    ErrorState,
    SelectField,
    type SelectOption,
    TextField,
} from '@/shared/ui';

import { usePlanFlow } from '../hooks/planQueries';
import {
    DIRECTION_OPTION_LABELS,
    DIRECTIONS,
    type Direction,
    type PlannedFlow,
} from '../types/flow';
import { CURRENCIES, type Currency } from '../types/money';
import {
    buildRecurrence,
    CADENCE_LABELS,
    CADENCES,
    type Cadence,
    RECURRENCE_KIND_LABELS,
    RECURRENCE_KINDS,
    type Recurrence,
    type RecurrenceFormState,
    type RecurrenceKind,
} from '../types/recurrence';
import styles from './PlanFlowForm.module.css';

const DIRECTION_OPTIONS: readonly SelectOption[] = DIRECTIONS.map((direction) => ({
    value: direction,
    label: DIRECTION_OPTION_LABELS[direction],
}));

const CURRENCY_OPTIONS: readonly SelectOption[] = CURRENCIES.map((currency) => ({
    value: currency,
    label: currency,
}));

const CADENCE_OPTIONS: readonly SelectOption[] = CADENCES.map((cadence) => ({
    value: cadence,
    label: CADENCE_LABELS[cadence],
}));

const REPEATS_OPTIONS: readonly SelectOption[] = RECURRENCE_KINDS.map((kind) => ({
    value: kind,
    label: RECURRENCE_KIND_LABELS[kind],
}));

/** A new planned flow starts as money out, in shekels (see `OpenAccountForm`'s own defaults). */
const DEFAULT_DIRECTION: Direction = 'OUTFLOW';
const DEFAULT_CURRENCY: Currency = 'ILS';
const DEFAULT_CADENCE: Cadence = 'MONTHLY';
const DEFAULT_INTERVAL = '1';

/**
 * Matches a positive integer count, no leading zero and no zero itself: `interval` and `count`
 * are counts, never money (ADR-029), and the ledger's own domain constructors refuse anything
 * below one.
 */
const POSITIVE_INTEGER_PATTERN = '[1-9][0-9]*';

/** ADR-028 lists field `errors` only on a 400. */
const MALFORMED_REQUEST_STATUS = 400;

/**
 * Matches a control showing an error: the shared field frame marks it `aria-invalid` exactly while
 * it has one, and describes it by that error.
 */
const INVALID_CONTROL_SELECTOR = '[aria-invalid="true"]';

/**
 * The form's own field names. `amount` covers `MoneyDto`'s nested `amount.amount` - see
 * `formFieldFor` - so it is not itself a literal problem field name; every other member here is
 * both.
 */
const FIELD_NAMES = ['description', 'amount', 'direction', 'startsOn'] as const;

type FieldName = (typeof FIELD_NAMES)[number];

type FieldErrors = Partial<Record<FieldName, string>>;

/** A failed submission, split into what can be shown on a field and what cannot. */
interface SubmissionErrors {
    readonly fields: FieldErrors;
    /** The failure itself when no field explains it, for `ErrorState`; otherwise `null`. */
    readonly unplaced: Error | null;
}

/**
 * The form field a problem's field path names, or `undefined` when it names none.
 *
 * `amount.amount` is `MoneyDto`'s own nested member and the only way the Amount field can fail
 * validation on the wire, so it maps onto `amount` here; every other member is already flat.
 * Neither the recurrence's own members (`recurrence.kind`, `recurrence.completeForKind`) map to
 * anything - the same exclusion `RecordTransactionForm` makes for an entry row's fields - so a
 * 400 naming one falls to `ErrorState` instead of being silently dropped.
 */
function formFieldFor(problemField: string): FieldName | undefined {
    if (problemField === 'amount.amount') {
        return 'amount';
    }
    return FIELD_NAMES.find((name) => name === problemField);
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
 * A 400's field errors keyed by this form's own fields (see `formFieldFor`), or `undefined` when
 * any names a field this form cannot place, so the whole problem is shown instead of part of it
 * being dropped silently.
 */
function placeFieldErrors(errors: readonly ProblemFieldError[]): FieldErrors | undefined {
    const fields: FieldErrors = {};
    for (const { field, message } of errors) {
        const formField = formFieldFor(field);
        if (formField === undefined) {
            return undefined;
        }
        const earlier = fields[formField];
        fields[formField] =
            earlier === undefined ? message : `${earlier}${FIELD_MESSAGE_SEPARATOR}${message}`;
    }
    return fields;
}

/**
 * Where a failed submission's explanation belongs. Mirrors `OpenAccountForm` and
 * `RecordTransactionForm`'s rule: a 400 whose `errors` all name a field this form can place goes
 * on those fields, `amount.amount` included; anything else - a 422 `RULE_VIOLATED`, or an
 * `errors` entry naming the recurrence's own members - is left for `ErrorState`, which shows the
 * problem's detail and every listed field error.
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

/** What happens once a flow is planned. */
export interface PlanFlowFormProps {
    /** Called with the planned flow, once the flows list has been refetched to include it. */
    readonly onPlanned: (flow: PlannedFlow) => void;
}

/**
 * The form that plans a flow: a description, a positive amount and its currency, a direction, a
 * start date, and how it repeats.
 *
 * **Repeats** conditionally reveals Cadence and Every (`interval`) for `Until a date` and `A
 * number of times`, plus Until or Times depending on which is chosen. `buildRecurrence` maps the
 * chosen fields to the `recurrence` the ledger expects, omitting whichever members the kind does
 * not carry; `interval` and `count` are computed before the in-flight guard is set, so a value
 * native validation somehow let through and `buildRecurrence` cannot parse never leaves the form
 * stuck pending (mirrors `DefineBudgetForm`'s month parse).
 *
 * The amount is sent exactly as typed: a negative one is not normalised, so the ledger refuses it
 * with 422 and the owner sees why (ADR-015).
 *
 * Submit is disabled while the request is pending, and a submit event arriving anyway is ignored
 * (ADR-028: no double submit). On success the form resets and `onPlanned` is called. A failure is
 * shown where it can be acted on: a 400's field errors on their fields, and anything else in an
 * `ErrorState` above the form. Focus follows the failure there, the rule every form in the app
 * keeps.
 *
 * @param props - see `PlanFlowFormProps`.
 */
export function PlanFlowForm({ onPlanned }: PlanFlowFormProps) {
    const headingId = useId();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    /** Set from the moment a submit starts a request until that request settles. */
    const submitting = useRef(false);

    const [description, setDescription] = useState('');
    const [amount, setAmount] = useState('');
    const [currency, setCurrency] = useState<Currency>(DEFAULT_CURRENCY);
    const [direction, setDirection] = useState<Direction>(DEFAULT_DIRECTION);
    const [startsOn, setStartsOn] = useState(() => todayIsoDate(new Date()));
    const [recurrenceKind, setRecurrenceKind] = useState<RecurrenceKind>('ONCE');
    const [cadence, setCadence] = useState<Cadence>(DEFAULT_CADENCE);
    const [interval, setInterval] = useState(DEFAULT_INTERVAL);
    const [until, setUntil] = useState('');
    const [count, setCount] = useState('');

    const planFlow = usePlanFlow();
    const failure = planFlow.error;
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

    function resetForm() {
        setDescription('');
        setAmount('');
        setCurrency(DEFAULT_CURRENCY);
        setDirection(DEFAULT_DIRECTION);
        setStartsOn(todayIsoDate(new Date()));
        setRecurrenceKind('ONCE');
        setCadence(DEFAULT_CADENCE);
        setInterval(DEFAULT_INTERVAL);
        setUntil('');
        setCount('');
    }

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // The disabled button stops a click, not Enter in a field or a scripted submit.
        if (submitting.current) {
            return;
        }
        const form: RecurrenceFormState = { kind: recurrenceKind, cadence, interval, until, count };
        let recurrence: Recurrence;
        try {
            recurrence = buildRecurrence(form);
        } catch {
            // Native `required` and the `[1-9][0-9]*` pattern on Every and Times refuse a blank,
            // fractional, negative or zero value before the browser ever fires this handler, so
            // this catch is unreachable through the UI; a scripted or malformed value is refused
            // rather than setting `submitting` and locking the form on a recurrence the ledger
            // could never have accepted anyway. Computed before the ref is set, so a bad parse
            // never leaves submit stuck disabled.
            return;
        }

        submitting.current = true;
        planFlow.mutate(
            { description, amount: { amount, currency }, direction, startsOn, recurrence },
            {
                onSuccess: (flow) => {
                    resetForm();
                    onPlanned(flow);
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
                Plan a flow
            </h2>
            {errors.unplaced === null ? null : (
                <ErrorState ref={errorStateRef} error={errors.unplaced} />
            )}
            <form ref={formRef} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <TextField
                        label="Description"
                        required
                        autoComplete="off"
                        value={description}
                        error={errors.fields.description}
                        onChange={(event) => {
                            setDescription(event.target.value);
                        }}
                    />
                    <TextField
                        label="Amount"
                        inputMode="decimal"
                        required
                        autoComplete="off"
                        value={amount}
                        error={errors.fields.amount}
                        onChange={(event) => {
                            setAmount(event.target.value);
                        }}
                    />
                    <SelectField
                        label="Currency"
                        options={CURRENCY_OPTIONS}
                        value={currency}
                        onChange={(event) => {
                            setCurrency(oneOf(CURRENCIES, event.target.value));
                        }}
                    />
                    <SelectField
                        label="Direction"
                        options={DIRECTION_OPTIONS}
                        value={direction}
                        error={errors.fields.direction}
                        onChange={(event) => {
                            setDirection(oneOf(DIRECTIONS, event.target.value));
                        }}
                    />
                    <DateField
                        label="Starts on"
                        required
                        value={startsOn}
                        error={errors.fields.startsOn}
                        onChange={(event) => {
                            setStartsOn(event.target.value);
                        }}
                    />
                    <SelectField
                        label="Repeats"
                        options={REPEATS_OPTIONS}
                        value={recurrenceKind}
                        onChange={(event) => {
                            setRecurrenceKind(oneOf(RECURRENCE_KINDS, event.target.value));
                        }}
                    />
                    {recurrenceKind === 'ONCE' ? null : (
                        <>
                            <SelectField
                                label="Cadence"
                                options={CADENCE_OPTIONS}
                                value={cadence}
                                onChange={(event) => {
                                    setCadence(oneOf(CADENCES, event.target.value));
                                }}
                            />
                            <TextField
                                label="Every"
                                inputMode="numeric"
                                pattern={POSITIVE_INTEGER_PATTERN}
                                required
                                autoComplete="off"
                                value={interval}
                                onChange={(event) => {
                                    setInterval(event.target.value);
                                }}
                            />
                        </>
                    )}
                    {recurrenceKind === 'UNTIL' ? (
                        <DateField
                            label="Until"
                            required
                            value={until}
                            onChange={(event) => {
                                setUntil(event.target.value);
                            }}
                        />
                    ) : null}
                    {recurrenceKind === 'TIMES' ? (
                        <TextField
                            label="Times"
                            inputMode="numeric"
                            pattern={POSITIVE_INTEGER_PATTERN}
                            required
                            autoComplete="off"
                            value={count}
                            onChange={(event) => {
                                setCount(event.target.value);
                            }}
                        />
                    ) : null}
                </div>
                <div>
                    <Button type="submit" pending={planFlow.isPending}>
                        Plan flow
                    </Button>
                </div>
            </form>
        </section>
    );
}
