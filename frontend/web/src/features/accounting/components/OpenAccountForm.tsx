import { type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import { clearSettledOutcome } from '@/shared/lib/settledMutation';
import {
    Button,
    ErrorState,
    labelsByField,
    SelectField,
    type SelectOption,
    TextField,
} from '@/shared/ui';

import { DUPLICATE_ACCOUNT_NAME } from '../api/accountingApi';
import { useOpenAccount } from '../hooks/accountQueries';
import {
    ACCOUNT_TYPE_LABELS,
    ACCOUNT_TYPES,
    type Account,
    type AccountType,
} from '../types/account';
import { CURRENCIES, type Currency } from '../types/money';
import styles from './OpenAccountForm.module.css';

const TYPE_OPTIONS: readonly SelectOption[] = ACCOUNT_TYPES.map((type) => ({
    value: type,
    label: ACCOUNT_TYPE_LABELS[type],
}));

const CURRENCY_OPTIONS: readonly SelectOption[] = CURRENCIES.map((currency) => ({
    value: currency,
    label: currency,
}));

/** A new account starts as an asset in shekels, the currency the owner keeps books in. */
const DEFAULT_TYPE: AccountType = 'ASSET';
const DEFAULT_CURRENCY: Currency = 'ILS';

/** For a duplicate whose problem carries no prose of its own to put on the Name field. */
const DUPLICATE_NAME_MESSAGE = 'An account with this name is already open.';

/** ADR-028 lists field `errors` only on a 400. */
const MALFORMED_REQUEST_STATUS = 400;

/**
 * Matches a control showing an error: the shared field frame marks it `aria-invalid` exactly while
 * it has one, and describes it by that error.
 */
const INVALID_CONTROL_SELECTOR = '[aria-invalid="true"]';

/** The form's fields, named as the ledger names them in a problem's `errors`. */
const FIELD_NAMES = ['name', 'type', 'currency'] as const;

type FieldName = (typeof FIELD_NAMES)[number];

/** Each field's label, keyed by the request member it fills. */
const LABELS = { name: 'Name', type: 'Type', currency: 'Currency' } as const satisfies Record<
    FieldName,
    string
>;

/** A failed request's field errors, listed under the form's own labels (WCAG 3.3.1). */
const FIELD_LABEL = labelsByField(LABELS);

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

/**
 * Between two messages for the same field. A field shows one error string, and messages such as
 * "must not be blank" carry no end punctuation of their own, so a bare space would run them into
 * one sentence.
 */
const FIELD_MESSAGE_SEPARATOR = '; ';

/**
 * A 400's field errors keyed by form field, or `undefined` when any names a field this form does
 * not have, so the whole problem is shown instead of part of it being dropped. Several messages
 * for one field are kept, in order, joined by `FIELD_MESSAGE_SEPARATOR`.
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
 * Where a failed submission's explanation belongs.
 *
 * A duplicate name goes on the Name field. A 400 listing field errors that all name form fields
 * goes on those fields. `problem.errors` is absent when the client found it malformed, so a 400
 * without it - and anything else - is left for `ErrorState`, which shows the problem's detail.
 */
function submissionErrors(error: Error | null): SubmissionErrors {
    if (error instanceof ApiError && error.problem !== undefined) {
        const { problem } = error;
        if (problem.code === DUPLICATE_ACCOUNT_NAME) {
            return {
                fields: { name: problem.detail ?? problem.title ?? DUPLICATE_NAME_MESSAGE },
                unplaced: null,
            };
        }
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

/** What happens once an account is open. */
export interface OpenAccountFormProps {
    /** Called with the opened account, once the accounts list has been refetched to include it. */
    readonly onOpened: (account: Account) => void;
}

/**
 * The form that opens an account: a name, one of the five types and a currency.
 *
 * Submit is disabled while the request is pending, and a submit event arriving anyway is ignored
 * (ADR-028: no double submit). On success the form resets and `onOpened` is called. A failure is
 * shown where it can be acted on: a duplicate name on the Name field, a 400's field errors on
 * their fields, and anything else in an `ErrorState` above the form. Editing any field clears a
 * failure that has settled, since it answered the fields as they were sent.
 *
 * Focus follows the failure there, which is the rule every form in the app keeps: to the first
 * field showing an error, which that error describes, so a screen reader reads the two together;
 * or, when no field explains the failure, to the `ErrorState`. Otherwise focus would stay on the
 * submit button, disabled while the request ran, and the failure would go unheard.
 *
 * @param props - see `OpenAccountFormProps`.
 */
export function OpenAccountForm({ onOpened }: OpenAccountFormProps) {
    const headingId = useId();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    /** Set from the moment a submit starts a request until that request settles. */
    const submitting = useRef(false);
    const [name, setName] = useState('');
    const [type, setType] = useState<AccountType>(DEFAULT_TYPE);
    const [currency, setCurrency] = useState<Currency>(DEFAULT_CURRENCY);
    const openAccount = useOpenAccount();
    const failure = openAccount.error;
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
        // The disabled button stops a click, not Enter in a field or a scripted submit, and a
        // second create is a second account. The flag is a ref rather than `isPending`, because
        // two submit events can arrive before the pending state has rendered.
        if (submitting.current) {
            return;
        }
        submitting.current = true;
        openAccount.mutate(
            { name, type, currency },
            {
                onSuccess: (account) => {
                    setName('');
                    setType(DEFAULT_TYPE);
                    setCurrency(DEFAULT_CURRENCY);
                    onOpened(account);
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
                Open an account
            </h2>
            {errors.unplaced === null ? null : (
                <ErrorState ref={errorStateRef} error={errors.unplaced} fieldLabel={FIELD_LABEL} />
            )}
            <form ref={formRef} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <TextField
                        label={LABELS.name}
                        name="name"
                        required
                        autoComplete="off"
                        value={name}
                        error={errors.fields.name}
                        onChange={(event) => {
                            clearSettledOutcome(openAccount, submitting);
                            setName(event.target.value);
                        }}
                    />
                    <SelectField
                        label={LABELS.type}
                        name="type"
                        options={TYPE_OPTIONS}
                        value={type}
                        error={errors.fields.type}
                        onChange={(event) => {
                            clearSettledOutcome(openAccount, submitting);
                            setType(oneOf(ACCOUNT_TYPES, event.target.value));
                        }}
                    />
                    <SelectField
                        label={LABELS.currency}
                        name="currency"
                        options={CURRENCY_OPTIONS}
                        value={currency}
                        error={errors.fields.currency}
                        onChange={(event) => {
                            clearSettledOutcome(openAccount, submitting);
                            setCurrency(oneOf(CURRENCIES, event.target.value));
                        }}
                    />
                </div>
                <div>
                    <Button type="submit" pending={openAccount.isPending}>
                        Open account
                    </Button>
                </div>
            </form>
        </section>
    );
}
