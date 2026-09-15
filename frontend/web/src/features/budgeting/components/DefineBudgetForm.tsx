import { type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import {
    Button,
    ErrorState,
    MonthField,
    SelectField,
    type SelectOption,
    TextField,
} from '@/shared/ui';

import { useDefineBudget } from '../hooks/budgetQueries';
import type { Budget, CategoryLimit } from '../types/budget';
import { CURRENCIES, type Currency } from '../types/money';
import { monthInputToPeriod } from '../types/period';
import styles from './DefineBudgetForm.module.css';

const CURRENCY_OPTIONS: readonly SelectOption[] = CURRENCIES.map((currency) => ({
    value: currency,
    label: currency,
}));

/** A new budget starts in shekels, the currency the owner keeps books in (see `OpenAccountForm`). */
const DEFAULT_CURRENCY: Currency = 'ILS';

/**
 * `YYYY-MM`, month `01`-`12` - mirrors `period.ts`'s own `MONTH_INPUT`, so a browser with no
 * month-picker UI (which renders the control as plain text) still refuses `00` or `13`+ through
 * native constraint validation, the same way a real month picker never offers them.
 */
const MONTH_INPUT_PATTERN = '\\d{4}-(0[1-9]|1[0-2])';

/** ADR-028 lists field `errors` only on a 400. */
const MALFORMED_REQUEST_STATUS = 400;

/**
 * Matches a control showing an error: the shared field frame marks it `aria-invalid` exactly while
 * it has one, and describes it by that error.
 */
const INVALID_CONTROL_SELECTOR = '[aria-invalid="true"]';

/**
 * The form's top-level fields, named as the ledger names them in a problem's `errors`. A limit
 * row's own `category` and `limit` are not among them - the same exclusion
 * `RecordTransactionForm` makes for an entry row's fields - so a 400 naming one falls to
 * `ErrorState` instead of being silently dropped.
 */
const FIELD_NAMES = ['currency'] as const;

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
 * field this form does not have at top level, so the whole problem is shown instead of part of it
 * being dropped silently.
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
 * Where a failed submission's explanation belongs. Mirrors `OpenAccountForm` and
 * `RecordTransactionForm`'s rule: a 400 whose `errors` all name a field this form has at top
 * level goes on those fields; anything else - a 422 `RULE_VIOLATED`, or an `errors` entry naming
 * a limit row's own field - is left for `ErrorState`, which shows the problem's detail.
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

/** One limit row's form state: a stable key, a category and a limit amount. */
interface LimitRowState {
    /** Stable across add and remove, so React never confuses one row's inputs for another's. */
    readonly key: number;
    readonly category: string;
    readonly amount: string;
}

const NEW_ROW: Omit<LimitRowState, 'key'> = { category: '', amount: '' };

/** The one row the form starts with. */
function initialRows(): LimitRowState[] {
    return [{ key: 0, ...NEW_ROW }];
}

/**
 * Every row mapped to a `CategoryLimit` in `currency`, with no arithmetic and no validation of
 * its own (ADR-029): both fields are native `required`, so the browser refuses to submit the
 * form while either is empty, and a row reaching here always has both filled. There is
 * accordingly no row-error state to hold here, unlike `RecordTransactionForm`'s entries, whose
 * debit/credit pair can be ambiguous in a way native `required` cannot catch.
 */
function buildLimits(rows: readonly LimitRowState[], currency: Currency): readonly CategoryLimit[] {
    return rows.map((row) => ({ category: row.category, limit: { amount: row.amount, currency } }));
}

/** What one limit row's fields need: its position, its state, and how to change it. */
interface LimitRowFieldsProps {
    readonly index: number;
    readonly row: LimitRowState;
    readonly removable: boolean;
    readonly onCategoryChange: (category: string) => void;
    readonly onAmountChange: (amount: string) => void;
    readonly onRemove: () => void;
}

/** One limit row: a category, a limit amount, and a remove button. */
function LimitRowFields({
    index,
    row,
    removable,
    onCategoryChange,
    onAmountChange,
    onRemove,
}: LimitRowFieldsProps) {
    const label = index + 1;
    return (
        <div className={styles.row}>
            <TextField
                label={`Category ${String(label)}`}
                required
                autoComplete="off"
                value={row.category}
                onChange={(event) => {
                    onCategoryChange(event.target.value);
                }}
            />
            <TextField
                label={`Limit amount ${String(label)}`}
                inputMode="decimal"
                required
                autoComplete="off"
                value={row.amount}
                onChange={(event) => {
                    onAmountChange(event.target.value);
                }}
            />
            <Button type="button" variant="secondary" disabled={!removable} onClick={onRemove}>
                {`Remove limit ${String(label)}`}
            </Button>
        </div>
    );
}

/** What happens once a budget is defined. */
export interface DefineBudgetFormProps {
    /** Called with the defined budget, once the budgets list has been refetched to include it. */
    readonly onDefined: (budget: Budget) => void;
}

/**
 * The form that defines a budget: a month, a currency, and at least one category limit row.
 *
 * Submit is disabled while the request is pending, and a submit event arriving anyway is ignored
 * (ADR-028: no double submit). On success the form resets and `onDefined` is called. A failure is
 * shown where it can be acted on: a 400's top-level field errors on their fields, and anything
 * else - including a 422 `RULE_VIOLATED` for a rule the domain refuses - in an `ErrorState` above
 * the form, exactly as `OpenAccountForm` and `RecordTransactionForm` do. Focus follows the
 * failure there.
 *
 * @param props - see `DefineBudgetFormProps`.
 */
export function DefineBudgetForm({ onDefined }: DefineBudgetFormProps) {
    const headingId = useId();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    /** Set from the moment a submit starts a request until that request settles. */
    const submitting = useRef(false);
    const nextKey = useRef(1);

    const [month, setMonth] = useState('');
    const [currency, setCurrency] = useState<Currency>(DEFAULT_CURRENCY);
    const [rows, setRows] = useState<LimitRowState[]>(initialRows);
    const defineBudget = useDefineBudget();
    const failure = defineBudget.error;
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

    function updateCategory(key: number, category: string) {
        setRows((previous) =>
            previous.map((row) => (row.key === key ? { ...row, category } : row)),
        );
    }

    function updateAmount(key: number, amount: string) {
        setRows((previous) => previous.map((row) => (row.key === key ? { ...row, amount } : row)));
    }

    function addRow() {
        // The key is read and advanced here, in the event handler, rather than inside the
        // updater below: React may invoke an updater more than once for one state transition
        // (Strict Mode's double-invoke among them), and an updater that mutates a ref each time
        // it runs would skip a key on every extra invocation.
        const key = nextKey.current;
        nextKey.current += 1;
        setRows((previous) => [...previous, { key, ...NEW_ROW }]);
    }

    function removeRow(key: number) {
        setRows((previous) =>
            previous.length <= 1 ? previous : previous.filter((row) => row.key !== key),
        );
    }

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // The disabled button stops a click, not Enter in a field or a scripted submit, and a
        // second define is a second budget. The flag is a ref rather than `isPending`, because
        // two submit events can arrive before the pending state has rendered.
        if (submitting.current) {
            return;
        }
        let period;
        try {
            period = monthInputToPeriod(month);
        } catch {
            // The month field's native picker, and its `pattern` fallback, keep this
            // unreachable through the UI; a scripted or malformed value is refused rather than
            // setting `submitting` and locking the form on a period the ledger could never have
            // accepted anyway. Computed before the ref is set, so a bad parse never leaves
            // submit stuck disabled.
            return;
        }
        submitting.current = true;
        defineBudget.mutate(
            { period, currency, limits: buildLimits(rows, currency) },
            {
                onSuccess: (budget) => {
                    setMonth('');
                    setCurrency(DEFAULT_CURRENCY);
                    setRows(initialRows());
                    nextKey.current = 1;
                    onDefined(budget);
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
                Define a budget
            </h2>
            {errors.unplaced === null ? null : (
                <ErrorState ref={errorStateRef} error={errors.unplaced} />
            )}
            <form ref={formRef} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <MonthField
                        label="Month"
                        required
                        pattern={MONTH_INPUT_PATTERN}
                        value={month}
                        onChange={(event) => {
                            setMonth(event.target.value);
                        }}
                    />
                    <SelectField
                        label="Currency"
                        options={CURRENCY_OPTIONS}
                        value={currency}
                        error={errors.fields.currency}
                        onChange={(event) => {
                            setCurrency(oneOf(CURRENCIES, event.target.value));
                        }}
                    />
                </div>
                <div className={styles.rows}>
                    {rows.map((row, index) => (
                        <LimitRowFields
                            key={row.key}
                            index={index}
                            row={row}
                            removable={rows.length > 1}
                            onCategoryChange={(category) => {
                                updateCategory(row.key, category);
                            }}
                            onAmountChange={(amount) => {
                                updateAmount(row.key, amount);
                            }}
                            onRemove={() => {
                                removeRow(row.key);
                            }}
                        />
                    ))}
                </div>
                <div className={styles.actions}>
                    <Button type="button" variant="secondary" onClick={addRow}>
                        Add category
                    </Button>
                </div>
                <div>
                    <Button type="submit" pending={defineBudget.isPending}>
                        Define budget
                    </Button>
                </div>
            </form>
        </section>
    );
}
