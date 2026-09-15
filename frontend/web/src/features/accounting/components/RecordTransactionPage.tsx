import { type ReactNode, type Ref, type SubmitEvent, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import { todayIsoDate } from '@/shared/lib/today';
import {
    Button,
    DateField,
    ErrorState,
    QueryErrorState,
    SelectField,
    type SelectOption,
    TextField,
    useRetryFocus,
} from '@/shared/ui';

import { useAccounts, useRecordTransaction } from '../hooks/accountQueries';
import type { Account } from '../types/account';
import { buildEntries, type EntryRowState } from '../types/transaction';
import styles from './RecordTransactionPage.module.css';
import { accountPath } from './paths';

/** A transaction needs at least two entries (ADR-011); the form starts and never drops below it. */
const MINIMUM_ROWS = 2;

/**
 * Matches a control showing an error: the shared field frame marks it `aria-invalid` exactly while
 * it has one, and describes it by that error.
 */
const INVALID_CONTROL_SELECTOR = '[aria-invalid="true"]';

/** The offered option for a row whose account is not yet chosen. */
const CHOOSE_ACCOUNT_OPTION: SelectOption = { value: '', label: 'Choose an account' };

/** The form's top-level fields, named as the ledger names them in a problem's `errors`. */
const FIELD_NAMES = ['description', 'occurredOn'] as const;

type FieldName = (typeof FIELD_NAMES)[number];

type FieldErrors = Partial<Record<FieldName, string>>;

/** Between two messages for the same field; see `OpenAccountForm`. */
const FIELD_MESSAGE_SEPARATOR = '; ';

/** ADR-028 lists field `errors` only on a 400. */
const MALFORMED_REQUEST_STATUS = 400;

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
 * A 400's field errors keyed by this form's top-level fields, or `undefined` when any names a
 * field this form does not have at top level - such as one inside `entries` - so the whole
 * problem is shown instead of part of it being dropped silently.
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
 * Where a failed submission's explanation belongs. Mirrors `OpenAccountForm`'s rule: a 400 whose
 * `errors` all name a field this form has goes on those fields; anything else - a 422
 * `RULE_VIOLATED`, `UNKNOWN_ACCOUNT` or `CURRENCY_MISMATCH`, an `errors` entry naming an entry's
 * own field - is left for `ErrorState`, which shows the problem's detail.
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

const NEW_ROW: Omit<EntryRowState, 'key'> = { accountId: '', debit: '', credit: '' };

/** The two rows the form starts with. */
function initialRows(): EntryRowState[] {
    return [
        { key: 0, ...NEW_ROW },
        { key: 1, ...NEW_ROW },
    ];
}

/** `name (currency)` for every open account, after the "choose one" placeholder. */
function accountOptions(accounts: readonly Account[]): SelectOption[] {
    return [
        CHOOSE_ACCOUNT_OPTION,
        ...accounts.map((account) => ({
            value: account.id,
            label: `${account.name} (${account.currency})`,
        })),
    ];
}

/** What one entry row's fields need: its position, its state, and how to change it. */
interface EntryRowFieldsProps {
    readonly index: number;
    readonly row: EntryRowState;
    readonly accounts: readonly Account[];
    /** The row's amount-field error, if any - an account error is never possible (see `buildEntries`). */
    readonly error: string | undefined;
    readonly removable: boolean;
    readonly onAccountChange: (accountId: string) => void;
    readonly onDebitChange: (value: string) => void;
    readonly onCreditChange: (value: string) => void;
    readonly onRemove: () => void;
}

/** One entry row: an account, a debit, a credit, and a remove button. */
function EntryRowFields({
    index,
    row,
    accounts,
    error,
    removable,
    onAccountChange,
    onDebitChange,
    onCreditChange,
    onRemove,
}: EntryRowFieldsProps) {
    const label = index + 1;
    return (
        <div className={styles.row}>
            <SelectField
                label={`Account ${String(label)}`}
                required
                options={accountOptions(accounts)}
                value={row.accountId}
                onChange={(event) => {
                    onAccountChange(event.target.value);
                }}
            />
            <TextField
                label={`Debit ${String(label)}`}
                inputMode="decimal"
                autoComplete="off"
                value={row.debit}
                error={error}
                onChange={(event) => {
                    onDebitChange(event.target.value);
                }}
            />
            <TextField
                label={`Credit ${String(label)}`}
                inputMode="decimal"
                autoComplete="off"
                value={row.credit}
                error={error}
                onChange={(event) => {
                    onCreditChange(event.target.value);
                }}
            />
            <Button type="button" variant="secondary" disabled={!removable} onClick={onRemove}>
                {`Remove entry ${String(label)}`}
            </Button>
        </div>
    );
}

/**
 * The form that records a transaction by hand: a date, a description, and at least two entry
 * rows, each an account with a debit or a credit.
 *
 * **The two-entry mirror** (ADR-029): while it is still active, typing a debit on row 1 copies
 * that string into row 2's credit, and typing a credit on row 1 copies it into row 2's debit - a
 * plain double entry balances itself without arithmetic. It stops for good, never to resume, the
 * moment any of the following happens: the owner adds a row, removes a row, or directly edits an
 * amount on any row other than row 1 - the one it mirrors from. Row 1 stays mirrorable only until
 * one of those happens once; there is no scenario in which it comes back.
 *
 * Submit is disabled while the request is pending, and a submit event arriving anyway is ignored
 * (ADR-028: no double submit). On success every account named in the entries gains its own
 * refetched balance and transaction list, and the browser moves on to the first entry's account;
 * the accounts list itself is never refetched, since no account's own fields can change from a
 * transaction. A failure is shown where it can be acted on: a client-side row problem on that
 * row, a 400's top-level field errors on their fields, and anything else - including a 422
 * `RULE_VIOLATED` - in an `ErrorState` above the form. Focus follows the failure there, the same
 * rule every form in the app keeps.
 */
function RecordTransactionForm({
    accounts,
    accountsFailure,
    headingRef,
}: {
    readonly accounts: readonly Account[];
    /** A failed background refetch of the accounts list, shown above the form; `null` otherwise. */
    readonly accountsFailure: ReactNode;
    /** The form's heading, where a retry that restored the accounts list moves focus. */
    readonly headingRef: Ref<HTMLHeadingElement>;
}) {
    const navigate = useNavigate();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    const submitting = useRef(false);
    const nextKey = useRef(MINIMUM_ROWS);

    const [occurredOn, setOccurredOn] = useState(() => todayIsoDate(new Date()));
    const [description, setDescription] = useState('');
    const [rows, setRows] = useState<EntryRowState[]>(initialRows);
    const [mirrorActive, setMirrorActive] = useState(true);
    const [rowErrors, setRowErrors] = useState<ReadonlyMap<number, string>>(new Map());

    const record = useRecordTransaction();
    const failure = record.error;
    const errors = submissionErrors(failure);
    const hasRowError = rowErrors.size > 0;

    // Runs after the render that shows the failure, so the invalid controls and the error state
    // are already on the page.
    useEffect(() => {
        if (!hasRowError && failure === null) {
            return;
        }
        const invalidControl =
            formRef.current?.querySelector<HTMLElement>(INVALID_CONTROL_SELECTOR);
        (invalidControl ?? errorStateRef.current)?.focus();
        // hasRowError is derived from rowErrors every render; listing rowErrors is what actually
        // identifies a new validation attempt.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [rowErrors, failure]);

    function updateAccount(index: number, accountId: string) {
        setRows((previous) =>
            previous.map((row, i) => (i === index ? { ...row, accountId } : row)),
        );
    }

    function updateDebit(index: number, value: string) {
        setRows((previous) => {
            const next = previous.map((row, i) => (i === index ? { ...row, debit: value } : row));
            if (index === 0 && mirrorActive) {
                const partner = next[1];
                if (partner !== undefined) {
                    next[1] = { ...partner, credit: value };
                }
            }
            return next;
        });
        // Row 1 is the mirror source and never disables it; any other row's amount being edited
        // directly is the owner overriding the mirror, which then stops for good.
        if (index !== 0) {
            setMirrorActive(false);
        }
    }

    function updateCredit(index: number, value: string) {
        setRows((previous) => {
            const next = previous.map((row, i) => (i === index ? { ...row, credit: value } : row));
            if (index === 0 && mirrorActive) {
                const partner = next[1];
                if (partner !== undefined) {
                    next[1] = { ...partner, debit: value };
                }
            }
            return next;
        });
        if (index !== 0) {
            setMirrorActive(false);
        }
    }

    function addRow() {
        // Adding a row stops the mirror for good: there is no longer a single "row 2" the owner
        // unambiguously means, so guessing which row to write into would be as likely to
        // surprise as to help.
        setMirrorActive(false);
        setRows((previous) => {
            const key = nextKey.current;
            nextKey.current += 1;
            return [...previous, { key, ...NEW_ROW }];
        });
    }

    function removeRow(key: number) {
        // Removing a row stops the mirror for good too: whichever row now sits at position 1 may
        // hold an amount the owner already typed there on purpose, and the mirror must never
        // overwrite it.
        setMirrorActive(false);
        setRows((previous) =>
            previous.length <= MINIMUM_ROWS ? previous : previous.filter((row) => row.key !== key),
        );
    }

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // The disabled button stops a click, not Enter in a field or a scripted submit.
        if (submitting.current) {
            return;
        }
        const built = buildEntries(rows, accounts);
        if ('rowErrors' in built) {
            setRowErrors(built.rowErrors);
            return;
        }
        setRowErrors(new Map());

        submitting.current = true;
        record.mutate(
            { occurredOn, description, entries: built.entries },
            {
                onSuccess: (transaction) => {
                    const firstAccountId = transaction.entries[0]?.account;
                    if (firstAccountId !== undefined) {
                        void navigate(accountPath(firstAccountId));
                    }
                },
                onSettled: () => {
                    submitting.current = false;
                },
            },
        );
    }

    return (
        <>
            <h1 ref={headingRef} tabIndex={-1}>
                Record a transaction
            </h1>
            {accountsFailure}
            {errors.unplaced === null ? null : (
                <ErrorState ref={errorStateRef} error={errors.unplaced} />
            )}
            <form ref={formRef} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <DateField
                        label="Date"
                        required
                        value={occurredOn}
                        error={errors.fields.occurredOn}
                        onChange={(event) => {
                            setOccurredOn(event.target.value);
                        }}
                    />
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
                </div>
                <div className={styles.rows}>
                    {rows.map((row, index) => (
                        <EntryRowFields
                            key={row.key}
                            index={index}
                            row={row}
                            accounts={accounts}
                            error={rowErrors.get(row.key)}
                            removable={rows.length > MINIMUM_ROWS}
                            onAccountChange={(accountId) => {
                                updateAccount(index, accountId);
                            }}
                            onDebitChange={(value) => {
                                updateDebit(index, value);
                            }}
                            onCreditChange={(value) => {
                                updateCredit(index, value);
                            }}
                            onRemove={() => {
                                removeRow(row.key);
                            }}
                        />
                    ))}
                </div>
                <div className={styles.actions}>
                    <Button type="button" variant="secondary" onClick={addRow}>
                        Add entry
                    </Button>
                </div>
                <div>
                    <Button type="submit" pending={record.isPending}>
                        Record transaction
                    </Button>
                </div>
            </form>
        </>
    );
}

/**
 * `/accounts/transactions/new`: the form that records a transaction by hand (ADR-029).
 *
 * The account list must be loaded first, since every row's currency comes from the chosen
 * account rather than being asked for separately.
 */
export function RecordTransactionPage() {
    const accounts = useAccounts();
    const { targetRef, onRetry } = useRetryFocus<HTMLHeadingElement>(
        accounts.error,
        accounts.isFetching,
        () => {
            void accounts.refetch();
        },
    );

    if (accounts.isPending) {
        return (
            <>
                <h1>Record a transaction</h1>
                <p className={styles.placeholder}>Loading accounts…</p>
            </>
        );
    }
    const accountsFailure = (
        <QueryErrorState error={accounts.error} retrying={accounts.isFetching} onRetry={onRetry} />
    );
    if (accounts.data === undefined) {
        return (
            <>
                <h1>Record a transaction</h1>
                {accountsFailure}
            </>
        );
    }
    // A failed background refetch never hides the form already on screen: TanStack Query keeps
    // the data it holds, so the failure is shown above the form rather than in its place.
    return (
        <RecordTransactionForm
            accounts={accounts.data}
            accountsFailure={accountsFailure}
            headingRef={targetRef}
        />
    );
}
