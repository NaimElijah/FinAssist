import {
    type ReactNode,
    type Ref,
    type SubmitEvent,
    useEffect,
    useId,
    useRef,
    useState,
} from 'react';
import { useNavigate } from 'react-router';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import { DECIMAL_HINT } from '@/shared/api/decimal';
import { indexedField } from '@/shared/api/fieldPath';
import { clearSettledOutcome } from '@/shared/lib/settledMutation';
import { todayIsoDate } from '@/shared/lib/today';
import { useWriteAccess } from '@/shared/lib/writeAccess';
import {
    Button,
    DateField,
    ErrorState,
    labelsByField,
    LoadingNote,
    QueryErrorState,
    SelectField,
    type SelectOption,
    StatusLine,
    TextField,
    useRetryFocus,
    useRowRemovalFocus,
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

/**
 * What `entryRowAmount` holds every row to: one of its two amounts filled, never both or neither.
 * Stated once above the rows, since it binds them all alike, and read after each amount's own hint.
 */
const ENTRY_RULE = 'Each entry is a debit or a credit, not both.';

/** The offered option for a row whose account is not yet chosen. */
const CHOOSE_ACCOUNT_OPTION: SelectOption = { value: '', label: 'Choose an account' };

/** The form's top-level fields, named as the ledger names them in a problem's `errors`. */
const FIELD_NAMES = ['description', 'occurredOn'] as const;

type FieldName = (typeof FIELD_NAMES)[number];

/** The labels of the fields outside the entry rows, keyed by the request member each fills. */
const LABELS = { occurredOn: 'Date', description: 'Description' } as const satisfies Record<
    FieldName,
    string
>;

/** Entry row `position`'s Account label, numbered from one as the rows are shown. */
function accountLabel(position: number): string {
    return `Account ${String(position)}`;
}

/** Entry row `position`'s Debit label. */
function debitLabel(position: number): string {
    return `Debit ${String(position)}`;
}

/** Entry row `position`'s Credit label. */
function creditLabel(position: number): string {
    return `Credit ${String(position)}`;
}

const TOP_LEVEL_LABEL = labelsByField(LABELS);

/**
 * A failed request's field errors, listed under the form's own labels (WCAG 3.3.1).
 *
 * An entry is named by its index in `entries`, which follows the rows' order; a failure is
 * cleared by any edit to the rows, so the rows cannot have moved while it is shown. An entry's
 * amount is typed as a debit or a credit, and its currency is its account's own.
 */
function fieldLabel(field: string): string | undefined {
    const entry = indexedField(field, 'entries');
    if (entry === undefined) {
        return TOP_LEVEL_LABEL(field);
    }
    switch (entry.member) {
        case 'account':
        case 'amount.currency':
            return accountLabel(entry.position);
        case 'amount':
        case 'amount.amount':
            return `${debitLabel(entry.position)} or ${creditLabel(entry.position)}`;
        default:
            return undefined;
    }
}

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
    /**
     * The row's amount error, if any - an account error is never possible (see `buildEntries`).
     * It belongs to the debit/credit pair, so it is shown once beneath the row and both fields
     * are marked invalid and described by it.
     */
    readonly error: string | undefined;
    /** The id of `ENTRY_RULE`, shown once above the rows, which describes both amounts. */
    readonly ruleId: string;
    readonly removable: boolean;
    readonly onAccountChange: (accountId: string) => void;
    readonly onDebitChange: (value: string) => void;
    readonly onCreditChange: (value: string) => void;
    readonly onRemove: () => void;
}

/** One entry row: an account, a debit, a credit, a remove button, and the row's error if any. */
function EntryRowFields({
    index,
    row,
    accounts,
    error,
    ruleId,
    removable,
    onAccountChange,
    onDebitChange,
    onCreditChange,
    onRemove,
}: EntryRowFieldsProps) {
    const label = index + 1;
    const errorId = useId();
    const sharedErrorId = error === undefined ? undefined : errorId;
    return (
        <div className={styles.row}>
            <SelectField
                label={accountLabel(label)}
                required
                options={accountOptions(accounts)}
                value={row.accountId}
                onChange={(event) => {
                    onAccountChange(event.target.value);
                }}
            />
            <TextField
                label={debitLabel(label)}
                hint={DECIMAL_HINT}
                sharedHintId={ruleId}
                inputMode="decimal"
                autoComplete="off"
                value={row.debit}
                sharedErrorId={sharedErrorId}
                onChange={(event) => {
                    onDebitChange(event.target.value);
                }}
            />
            <TextField
                label={creditLabel(label)}
                hint={DECIMAL_HINT}
                sharedHintId={ruleId}
                inputMode="decimal"
                autoComplete="off"
                value={row.credit}
                sharedErrorId={sharedErrorId}
                onChange={(event) => {
                    onCreditChange(event.target.value);
                }}
            />
            <Button type="button" variant="secondary" disabled={!removable} onClick={onRemove}>
                {`Remove entry ${String(label)}`}
            </Button>
            {error === undefined ? null : (
                <p id={errorId} className={styles.rowError}>
                    {error}
                </p>
            )}
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
 * rule every form in the app keeps. Editing any field, or adding or removing a row, clears a
 * failure that has settled, since it answered the entries as they were sent; editing a row's
 * amount clears that row's own error, and row 2's too while the mirror writes into it.
 *
 * Adding a row is reported on a status line beside Add entry, since focus stays on the button.
 * Removing one moves focus to the row now in its place, or to Add entry when the last row went
 * (`useRowRemovalFocus`), since the pressed button goes with its row.
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
    const entryRuleId = useId();
    const submitting = useRef(false);
    const nextKey = useRef(MINIMUM_ROWS);

    const [occurredOn, setOccurredOn] = useState(() => todayIsoDate(new Date()));
    const [description, setDescription] = useState('');
    const [rows, setRows] = useState<EntryRowState[]>(initialRows);
    const [mirrorActive, setMirrorActive] = useState(true);
    const [rowErrors, setRowErrors] = useState<ReadonlyMap<number, string>>(new Map());
    // Counts the submits the rows refused, so focus moves to the first invalid control once per
    // refusal - never when an edit clears one row's error while another row's still stands.
    const [rowRefusals, setRowRefusals] = useState(0);
    const [rowsStatus, setRowsStatus] = useState('');
    const { rowsRef, addRowRef, onRowRemoved } = useRowRemovalFocus(rows.length);

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
        // hasRowError is derived from rowErrors every render; rowRefusals is what identifies a new
        // validation attempt, and rowErrors also changes when an edit clears one row's error.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [rowRefusals, failure]);

    /**
     * The keys of the rows an amount typed on row `index` changes: that row, and row 2 too while
     * the mirror copies row 1 into it.
     */
    function editedRowKeys(index: number): number[] {
        const mirrored = index === 0 && mirrorActive ? [1] : [];
        return [index, ...mirrored].flatMap((position) => {
            const row = rows[position];
            return row === undefined ? [] : [row.key];
        });
    }

    /** Drops the errors of the rows `keys` names: each described the row before this edit. */
    function clearRowErrors(keys: readonly number[]) {
        setRowErrors((previous) => {
            if (!keys.some((key) => previous.has(key))) {
                return previous;
            }
            const next = new Map(previous);
            for (const key of keys) {
                next.delete(key);
            }
            return next;
        });
    }

    function updateAccount(index: number, accountId: string) {
        clearSettledOutcome(record, submitting);
        setRows((previous) =>
            previous.map((row, i) => (i === index ? { ...row, accountId } : row)),
        );
    }

    function updateDebit(index: number, value: string) {
        clearSettledOutcome(record, submitting);
        clearRowErrors(editedRowKeys(index));
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
        clearSettledOutcome(record, submitting);
        clearRowErrors(editedRowKeys(index));
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
        clearSettledOutcome(record, submitting);
        // Adding a row stops the mirror for good: there is no longer a single "row 2" the owner
        // unambiguously means, so guessing which row to write into would be as likely to
        // surprise as to help.
        setMirrorActive(false);
        // The key is read and advanced here, in the event handler, not inside the updater below:
        // React may call an updater more than once for one update - Strict Mode does - and an
        // updater advancing a ref would skip a key on every extra call.
        const key = nextKey.current;
        nextKey.current += 1;
        setRows((previous) => [...previous, { key, ...NEW_ROW }]);
        setRowsStatus(`Entry ${String(rows.length + 1)} added.`);
    }

    function removeRow(key: number) {
        clearSettledOutcome(record, submitting);
        // A removal recorded but never made would move focus on the next change of row count.
        if (rows.length <= MINIMUM_ROWS) {
            return;
        }
        onRowRemoved(rows.findIndex((row) => row.key === key));
        // Emptied so that adding a row again is announced, even where its sentence is unchanged.
        setRowsStatus('');
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
            setRowRefusals((previous) => previous + 1);
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
                <ErrorState ref={errorStateRef} error={errors.unplaced} fieldLabel={fieldLabel} />
            )}
            <form ref={formRef} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <DateField
                        label={LABELS.occurredOn}
                        required
                        value={occurredOn}
                        error={errors.fields.occurredOn}
                        onChange={(event) => {
                            clearSettledOutcome(record, submitting);
                            setOccurredOn(event.target.value);
                        }}
                    />
                    <TextField
                        label={LABELS.description}
                        required
                        autoComplete="off"
                        value={description}
                        error={errors.fields.description}
                        onChange={(event) => {
                            clearSettledOutcome(record, submitting);
                            setDescription(event.target.value);
                        }}
                    />
                </div>
                <div className={styles.entries}>
                    {/* Outside rowsRef: useRowRemovalFocus reads its children as the rows. */}
                    <p id={entryRuleId} className={styles.entryRule}>
                        {ENTRY_RULE}
                    </p>
                    <div ref={rowsRef} className={styles.rows}>
                        {rows.map((row, index) => (
                            <EntryRowFields
                                key={row.key}
                                index={index}
                                row={row}
                                accounts={accounts}
                                error={rowErrors.get(row.key)}
                                ruleId={entryRuleId}
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
                </div>
                <div className={styles.actions}>
                    <Button ref={addRowRef} type="button" variant="secondary" onClick={addRow}>
                        Add entry
                    </Button>
                    <StatusLine message={rowsStatus} />
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
 * The calm state a read-only session sees instead of the form (ADR-032): naming the form it
 * cannot submit rather than a route that simply does not work. Skips `useAccounts` entirely, since
 * nothing here would use its data.
 */
function ReadOnlyRecordTransaction() {
    return (
        <>
            <h1>Record a transaction</h1>
            <p className={styles.placeholder}>Your access is read-only.</p>
        </>
    );
}

/**
 * `/accounts/transactions/new`: the form that records a transaction by hand (ADR-029), or the
 * calm read-only state above it for a session without write access.
 *
 * The account list must be loaded first, since every row's currency comes from the chosen
 * account rather than being asked for separately.
 */
export function RecordTransactionPage() {
    const { canWrite } = useWriteAccess();
    if (!canWrite) {
        return <ReadOnlyRecordTransaction />;
    }
    return <RecordTransactionScreen />;
}

/** The data-loading half of `RecordTransactionPage`, mounted only once write access is known. */
function RecordTransactionScreen() {
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
                <LoadingNote message="Loading accounts…" />
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
