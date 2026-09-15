import { type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import {
    Button,
    type Column,
    DataTable,
    DateText,
    EmptyState,
    ErrorState,
    MoneyText,
    SelectField,
    type SelectOption,
    TextField,
} from '@/shared/ui';

import { usePriceDisposal } from '../hooks/portfolioQueries';
import type { DisposalInput, Disposition, Lot } from '../types/disposal';
import { CURRENCIES, type Currency, gainOrLoss } from '../types/money';
import styles from './DisposalSection.module.css';
import { Figure, Figures } from './Figures';

const CURRENCY_OPTIONS: readonly SelectOption[] = CURRENCIES.map((currency) => ({
    value: currency,
    label: currency,
}));

/** Proceeds start in shekels, the currency the owner keeps books in, as every form's amount does. */
const DEFAULT_CURRENCY: Currency = 'ILS';

/** ADR-028 lists field `errors` only on a 400. */
const MALFORMED_REQUEST_STATUS = 400;

/**
 * Matches a control showing an error: the shared field frame marks it `aria-invalid` exactly while
 * it has one, and describes it by that error.
 */
const INVALID_CONTROL_SELECTOR = '[aria-invalid="true"]';

/**
 * Between two messages for the same field. A field shows one error string, and messages such as
 * "Field required" carry no end punctuation of their own, so a bare space would run them together.
 */
const FIELD_MESSAGE_SEPARATOR = '; ';

/** The form's fields a 400 can be placed on. */
type FieldName = 'quantity' | 'amount';

type FieldErrors = Partial<Record<FieldName, string>>;

/** A failed calculation, split into what can be shown on a field and what cannot. */
interface SubmissionErrors {
    readonly fields: FieldErrors;
    /** The failure itself when no field explains it, for `ErrorState`; otherwise `null`. */
    readonly unplaced: Error | null;
}

/**
 * The form field a problem's field path names, or `undefined` when it names none.
 *
 * The assistant names a body member by its dotted path, so the proceeds' amount arrives as
 * `proceeds.amount`. The currency is a select offering only codes the assistant accepts, so no
 * path maps to it; a 400 naming it, or any other path, falls to `ErrorState` instead of being
 * dropped silently.
 */
function formFieldFor(problemField: string): FieldName | undefined {
    switch (problemField) {
        case 'quantity':
            return 'quantity';
        case 'proceeds.amount':
            return 'amount';
        default:
            return undefined;
    }
}

/**
 * A 400's field errors keyed by this form's own fields (see `formFieldFor`), or `undefined` when
 * any names a field this form cannot place, so the whole problem is shown instead of part of it
 * being dropped silently. Several messages for one field are kept, in order.
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
 * Where a failed calculation's explanation belongs: a 400 whose `errors` all name a field this form
 * can place goes on those fields; anything else - a 422 `INSUFFICIENT_HOLDING`, `RULE_VIOLATED` or
 * `CURRENCY_MISMATCH` among them - is left for `ErrorState`, which shows the problem's detail.
 */
function submissionErrors(error: Error | null): SubmissionErrors {
    if (
        error instanceof ApiError &&
        error.status === MALFORMED_REQUEST_STATUS &&
        error.problem?.errors !== undefined &&
        error.problem.errors.length > 0
    ) {
        const fields = placeFieldErrors(error.problem.errors);
        if (fields !== undefined) {
            return { fields, unplaced: null };
        }
    }
    return { fields: {}, unplaced: error };
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

/** One remaining lot, and its place among those remaining. */
interface LotRow {
    readonly lot: Lot;
    /**
     * Oldest first, from 0. A lot carries no id on the wire, and two bought on one day at one price
     * are indistinguishable, so its place is its identity within a result; each new result replaces
     * the table whole and never reorders it.
     */
    readonly place: number;
}

const LOT_COLUMNS: readonly Column<LotRow>[] = [
    {
        key: 'acquired',
        header: 'Acquired',
        align: 'end',
        rowHeader: true,
        render: (row) => <DateText value={row.lot.acquiredOn} />,
    },
    {
        key: 'quantity',
        header: 'Quantity',
        align: 'end',
        // A number of units, not money: shown exactly as received, never formatted.
        render: (row) => row.lot.quantity,
    },
    {
        key: 'unitCost',
        header: 'Unit cost',
        align: 'end',
        render: (row) => (
            <MoneyText amount={row.lot.unitCost.amount} currency={row.lot.unitCost.currency} />
        ),
    },
];

/**
 * What a calculation found: the cost of sale, the realised gain or loss, and the lots that would
 * remain, oldest first.
 *
 * Its heading takes focus when it appears. Submit was disabled while the calculation ran, which
 * leaves focus nowhere, and the owner should hear the answer. A new calculation unmounts the
 * previous result while it runs, so every answer takes focus once.
 */
function DisposalResult({ disposition }: { readonly disposition: Disposition }) {
    const headingId = useId();
    const headingRef = useRef<HTMLHeadingElement>(null);
    const rows: readonly LotRow[] = disposition.remaining.map((lot, place) => ({ lot, place }));

    useEffect(() => {
        headingRef.current?.focus();
    }, []);

    return (
        <section aria-labelledby={headingId} className={styles.result}>
            <h3 id={headingId} ref={headingRef} tabIndex={-1} className={styles.resultHeading}>
                What this sale would realise
            </h3>
            <Figures>
                <Figure term="Cost of sale">
                    <MoneyText
                        amount={disposition.costOfSale.amount}
                        currency={disposition.costOfSale.currency}
                    />
                </Figure>
                <Figure term={`Realised ${gainOrLoss(disposition.realisedGain.amount)}`}>
                    <MoneyText
                        amount={disposition.realisedGain.amount}
                        currency={disposition.realisedGain.currency}
                    />
                </Figure>
            </Figures>
            {rows.length === 0 ? (
                <EmptyState message="No lots remaining after this disposal" />
            ) : (
                <DataTable
                    caption="Lots remaining after this disposal"
                    columns={LOT_COLUMNS}
                    rows={rows}
                    rowKey={(row) => String(row.place)}
                />
            )}
        </section>
    );
}

/** Which holding a disposal is priced for. */
export interface DisposalSectionProps {
    /** The symbol as it appears in the URL. */
    readonly symbol: string;
}

/**
 * The calculator that prices a disposal of one holding - a quantity, and proceeds in a currency -
 * and shows what selling would realise, lots consumed oldest first (ADR-013). A calculation that
 * records nothing, which a note beside the heading says and the form is described by.
 *
 * The quantity and amount are sent exactly as typed, never parsed or normalised, so a malformed or
 * non-positive one is refused by the assistant and the owner sees why (ADR-029).
 *
 * Submit is disabled while the calculation runs, and a submit event arriving anyway is ignored. A
 * failure is shown where it can be acted on: a 400's field errors on Quantity or Proceeds amount,
 * and anything else in an `ErrorState` above the form. Focus follows the failure there, the rule
 * every form in the app keeps.
 *
 * Its state belongs to one symbol, so its page keys it by symbol: moving to another holding starts
 * it afresh, rather than showing one holding's calculation under another's name.
 *
 * @param props - see `DisposalSectionProps`.
 */
export function DisposalSection({ symbol }: DisposalSectionProps) {
    const headingId = useId();
    const noteId = useId();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    /** Set from the moment a submit starts a calculation until it settles. */
    const submitting = useRef(false);
    const [quantity, setQuantity] = useState('');
    const [amount, setAmount] = useState('');
    const [currency, setCurrency] = useState<Currency>(DEFAULT_CURRENCY);
    const pricing = usePriceDisposal(symbol);
    const failure = pricing.error;
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

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // Pricing records nothing, so a duplicate would only be a wasted request, but one submit
        // still means one calculation. The disabled button stops a click, not Enter in a field or
        // a scripted submit, and the flag is a ref rather than `isPending` because two submit
        // events can arrive before the pending state has rendered.
        if (submitting.current) {
            return;
        }
        const input: DisposalInput = { quantity, proceeds: { amount, currency } };
        submitting.current = true;
        pricing.mutate(input, {
            onSettled: () => {
                submitting.current = false;
            },
        });
    }

    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                Price a disposal
            </h2>
            <p id={noteId} className={styles.note}>
                Calculation only — nothing is recorded.
            </p>
            {errors.unplaced === null ? null : (
                <ErrorState ref={errorStateRef} error={errors.unplaced} />
            )}
            <form ref={formRef} aria-describedby={noteId} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <TextField
                        label="Quantity"
                        name="quantity"
                        inputMode="decimal"
                        required
                        autoComplete="off"
                        value={quantity}
                        error={errors.fields.quantity}
                        onChange={(event) => {
                            setQuantity(event.target.value);
                        }}
                    />
                    <TextField
                        label="Proceeds amount"
                        name="proceedsAmount"
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
                        name="currency"
                        options={CURRENCY_OPTIONS}
                        value={currency}
                        onChange={(event) => {
                            setCurrency(oneOf(CURRENCIES, event.target.value));
                        }}
                    />
                </div>
                <div>
                    <Button type="submit" pending={pricing.isPending}>
                        Price disposal
                    </Button>
                </div>
            </form>
            {pricing.data === undefined ? null : <DisposalResult disposition={pricing.data} />}
        </section>
    );
}
