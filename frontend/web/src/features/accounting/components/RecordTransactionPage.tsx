import { type SubmitEvent, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';

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

import { useAccounts, useRecordTransaction } from '../hooks/accountQueries';
import type { Account } from '../types/account';
import { entryRowAmount, type Entry } from '../types/transaction';
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

/** One entry row's form state, before it is mapped to the wire. */
interface EntryRowState {
    /** Stable across add and remove, so React keys the row rather than its position. */
    readonly key: number;
    readonly accountId: string;
    readonly debit: string;
    readonly credit: string;
}

/** One row's client-side validation failure, if any. */
interface RowError {
    readonly account?: string;
    readonly amount?: string;
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

/**
 * Map every row to a wire entry, or collect what is wrong with each row instead.
 *
 * No arithmetic and no balance check happens here (ADR-029): a row's amount is mapped by
 * `entryRowAmount` alone, and whether the whole set sums to zero is the ledger's own check, as
 * 422 `RULE_VIOLATED`.
 */
function buildEntries(
    rows: readonly EntryRowState[],
    accounts: readonly Account[],
): { readonly entries: readonly Entry[] } | { readonly rowErrors: readonly RowError[] } {
    const rowErrors: RowError[] = [];
    const entries: Entry[] = [];
    let allValid = true;

    for (const row of rows) {
        const account = accounts.find((candidate) => candidate.id === row.accountId);
        const amountResult = entryRowAmount(row.debit, row.credit);
        const rowError: RowError = {
            ...(account === undefined ? { account: 'Choose an account' } : {}),
            ...(typeof amountResult === 'string' ? {} : { amount: amountResult.message }),
        };
        rowErrors.push(rowError);
        if (rowError.account !== undefined || rowError.amount !== undefined) {
            allValid = false;
        } else if (account !== undefined && typeof amountResult === 'string') {
            entries.push({
                account: account.id,
                amount: { amount: amountResult, currency: account.currency },
            });
        }
    }

    return allValid ? { entries } : { rowErrors };
}

/** A retry for the accounts read, busy while the retry is in flight. */
function AccountsRetryButton({
    isFetching,
    onRetry,
}: {
    readonly isFetching: boolean;
    readonly onRetry: () => void;
}) {
    return (
        <Button variant="secondary" pending={isFetching} onClick={onRetry}>
            Try again
        </Button>
    );
}

/** What one entry row's fields need: its position, its state, and how to change it. */
interface EntryRowFieldsProps {
    readonly index: number;
    readonly row: EntryRowState;
    readonly accounts: readonly Account[];
    readonly error: RowError | undefined;
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
                error={error?.account}
                onChange={(event) => {
                    onAccountChange(event.target.value);
                }}
            />
            <TextField
                label={`Debit ${String(label)}`}
                inputMode="decimal"
                autoComplete="off"
                value={row.debit}
                error={error?.amount}
                onChange={(event) => {
                    onDebitChange(event.target.value);
                }}
            />
            <TextField
                label={`Credit ${String(label)}`}
                inputMode="decimal"
                autoComplete="off"
                value={row.credit}
                error={error?.amount}
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
 * **The two-entry mirror** (ADR-029): while there are exactly two rows and the owner has not
 * edited row 2's amounts directly, typing a debit on row 1 copies that string into row 2's
 * credit, and typing a credit on row 1 copies it into row 2's debit - a plain double entry
 * balances itself without arithmetic. Editing row 2 directly, in either field, stops the mirror
 * for good; adding a third row stops it as long as there are more than two, but does not clear
 * the flag, so removing back down to two without having touched row 2 resumes it.
 *
 * Submit is disabled while the request is pending, and a submit event arriving anyway is ignored
 * (ADR-028: no double submit). On success every account named in the entries gains its own
 * refetched balance and transaction list, the accounts list is refetched too, and the browser
 * moves on to the first entry's account. A failure is shown where it can be acted on: a client-side
 * row problem on that row, a 400's top-level field errors on their fields, and anything else -
 * including a 422 `RULE_VIOLATED` - in an `ErrorState` above the form. Focus follows the failure
 * there, the same rule every form in the app keeps.
 */
function RecordTransactionForm({ accounts }: { readonly accounts: readonly Account[] }) {
    const navigate = useNavigate();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    const submitting = useRef(false);
    const nextKey = useRef(MINIMUM_ROWS);

    const [occurredOn, setOccurredOn] = useState(() => todayIsoDate(new Date()));
    const [description, setDescription] = useState('');
    const [rows, setRows] = useState<EntryRowState[]>(initialRows);
    const [mirrorActive, setMirrorActive] = useState(true);
    const [rowErrors, setRowErrors] = useState<readonly RowError[]>([]);

    const record = useRecordTransaction();
    const failure = record.error;
    const errors = submissionErrors(failure);
    const hasRowError = rowErrors.some(
        (error) => error.account !== undefined || error.amount !== undefined,
    );

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
            if (index === 0 && mirrorActive && next.length === MINIMUM_ROWS) {
                const partner = next[1];
                if (partner !== undefined) {
                    next[1] = { ...partner, credit: value };
                }
            }
            return next;
        });
        if (index === 1) {
            setMirrorActive(false);
        }
    }

    function updateCredit(index: number, value: string) {
        setRows((previous) => {
            const next = previous.map((row, i) => (i === index ? { ...row, credit: value } : row));
            if (index === 0 && mirrorActive && next.length === MINIMUM_ROWS) {
                const partner = next[1];
                if (partner !== undefined) {
                    next[1] = { ...partner, debit: value };
                }
            }
            return next;
        });
        if (index === 1) {
            setMirrorActive(false);
        }
    }

    function addRow() {
        setRows((previous) => {
            const key = nextKey.current;
            nextKey.current += 1;
            return [...previous, { key, ...NEW_ROW }];
        });
    }

    function removeRow(key: number) {
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
        setRowErrors([]);

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
            <h1>Record a transaction</h1>
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
                            error={rowErrors[index]}
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

    if (accounts.isPending) {
        return (
            <>
                <h1>Record a transaction</h1>
                <p className={styles.placeholder}>Loading accounts…</p>
            </>
        );
    }
    if (accounts.data === undefined) {
        return (
            <>
                <h1>Record a transaction</h1>
                <ErrorState
                    error={accounts.error}
                    action={
                        <AccountsRetryButton
                            isFetching={accounts.isFetching}
                            onRetry={() => {
                                void accounts.refetch();
                            }}
                        />
                    }
                />
            </>
        );
    }
    return <RecordTransactionForm accounts={accounts.data} />;
}
