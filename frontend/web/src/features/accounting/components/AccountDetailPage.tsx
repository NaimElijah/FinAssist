import type { UseQueryResult } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';

import { hasProblemCode } from '@/shared/api/client';
import { useDocumentTitle } from '@/shared/lib/documentTitle';
import { clearSettledOutcome } from '@/shared/lib/settledMutation';
import { todayIsoDate } from '@/shared/lib/today';
import {
    ActionPanel,
    Button,
    type Column,
    DataTable,
    DateField,
    DateText,
    EmptyState,
    ErrorState,
    MoneyText,
    QueryErrorState,
    StateBadge,
    TextField,
    type Tone,
    useRetryFocus,
} from '@/shared/ui';

import { ACCOUNT_NOT_FOUND } from '../api/accountingApi';
import {
    useAccountBalance,
    useAccounts,
    useAccountTransactions,
    useClearTransaction,
    useReconcileTransaction,
    useVoidTransaction,
} from '../hooks/accountQueries';
import { ACCOUNT_TYPE_LABELS } from '../types/account';
import type { Money } from '../types/money';
import type { Transaction, TransactionState, TransactionStatus } from '../types/transaction';
import styles from './AccountDetailPage.module.css';
import { ACCOUNTS_PATH } from './paths';

const NO_TRANSACTIONS_MESSAGE = 'No transactions touch this account yet';

/** Each status's colour role. The label always carries the meaning; tone only reinforces it. */
const STATUS_TONES: Readonly<Record<TransactionStatus, Tone>> = {
    PENDING: 'warning',
    CLEARED: 'neutral',
    RECONCILED: 'positive',
    VOIDED: 'negative',
};

/** The words shown for each status, rather than the wire's upper-case names. */
const STATUS_LABELS: Readonly<Record<TransactionStatus, string>> = {
    PENDING: 'Pending',
    CLEARED: 'Cleared',
    RECONCILED: 'Reconciled',
    VOIDED: 'Voided',
};

/** The three lifecycle actions a transaction can move through, each opening its own panel. */
type LifecycleKind = 'CLEAR' | 'RECONCILE' | 'VOID';

/** Which row's panel is open, and for which action; `undefined` when none is. */
interface ActiveAction {
    readonly transactionId: string;
    readonly kind: LifecycleKind;
}

/**
 * A just-closed panel's action, for restoring focus once it closes.
 *
 * `newStatus` is the transaction's status straight from the mutation's own response - known the
 * instant it succeeds, unlike the separate transactions-list query, whose refetch can still be in
 * flight when the panel closes. `undefined` means nothing changed: a cancel.
 */
interface ClosedAction extends ActiveAction {
    readonly newStatus?: TransactionStatus | undefined;
}

/**
 * The lifecycle actions each status legally admits, mirroring the ledger's own sealed
 * `TransactionState` transitions: `PENDING` clears or voids, `CLEARED` reconciles or voids, and a
 * `RECONCILED` or `VOIDED` transaction admits none. The server stays authoritative regardless: a
 * stale row offering an action it no longer allows still meets a 409 `ILLEGAL_TRANSITION`.
 */
const LEGAL_ACTIONS: Readonly<Record<TransactionStatus, readonly LifecycleKind[]>> = {
    PENDING: ['CLEAR', 'VOID'],
    CLEARED: ['RECONCILE', 'VOID'],
    RECONCILED: [],
    VOIDED: [],
};

const ACTION_LABELS: Readonly<Record<LifecycleKind, string>> = {
    CLEAR: 'Clear',
    RECONCILE: 'Reconcile',
    VOID: 'Void',
};

const ACTION_TITLES: Readonly<Record<LifecycleKind, string>> = {
    CLEAR: 'Clear transaction',
    RECONCILE: 'Reconcile transaction',
    VOID: 'Void transaction',
};

/** The id of one transaction's actions cell, so focus can be restored to it after its panel closes. */
function actionsCellId(transactionId: string): string {
    return `transaction-actions-${transactionId}`;
}

/** The id of one transaction's state cell, the fallback focus target when its own action is gone. */
function stateCellId(transactionId: string): string {
    return `transaction-state-${transactionId}`;
}

/** The buttons offering this status's legal actions, or nothing for a frozen transaction. */
function LifecycleButtons({
    status,
    onOpen,
}: {
    readonly status: TransactionStatus;
    readonly onOpen: (kind: LifecycleKind) => void;
}) {
    const actions = LEGAL_ACTIONS[status];
    if (actions.length === 0) {
        return null;
    }
    return (
        <span className={styles.actions}>
            {actions.map((kind) => (
                <Button
                    key={kind}
                    data-action={kind}
                    variant={kind === 'VOID' ? 'danger' : 'secondary'}
                    onClick={() => {
                        onOpen(kind);
                    }}
                >
                    {ACTION_LABELS[kind]}
                </Button>
            ))}
        </span>
    );
}

/**
 * The inline panel for one lifecycle action: a date defaulting to today, a reason when voiding,
 * and submit/cancel.
 *
 * On success it invalidates the balance and transactions of every account the transaction
 * touches, and closes. On failure - including a 409 `ILLEGAL_TRANSITION` when the server no
 * longer agrees this row's action is legal - it shows the server's detail and stays open, so the
 * owner can see why and try something else. Editing the date or the reason clears that failure,
 * since it answered the action as it was sent.
 *
 * The Reason field's own `required` is the whole of its client-side validation: the browser
 * refuses to fire the form's submit event while it is empty, so `submit` below is never even
 * called for an empty reason, and no duplicate check is needed here.
 */
function LifecycleActionPanel({
    kind,
    transactionId,
    onClose,
}: {
    readonly kind: LifecycleKind;
    readonly transactionId: string;
    /** Called with the transaction's new status on success, or with none on cancel. */
    readonly onClose: (newStatus?: TransactionStatus) => void;
}) {
    const clear = useClearTransaction();
    const reconcile = useReconcileTransaction();
    const voidTransaction = useVoidTransaction();
    const [on, setOn] = useState(() => todayIsoDate(new Date()));
    const [reason, setReason] = useState('');
    const submitting = useRef(false);
    const errorStateRef = useRef<HTMLDivElement>(null);

    const mutation = kind === 'CLEAR' ? clear : kind === 'RECONCILE' ? reconcile : voidTransaction;
    const failure = mutation.error;

    // Runs after the render that shows the failure, so the error state is already on the page.
    useEffect(() => {
        if (failure === null) {
            return;
        }
        errorStateRef.current?.focus();
    }, [failure]);

    function submit() {
        // The disabled submit stops a click, not a submit event from elsewhere.
        if (submitting.current) {
            return;
        }
        submitting.current = true;
        // The mutation's own response carries the transaction's new status; passed to onClose so
        // it can decide where focus goes without waiting on the separate transactions query.
        const onSuccess = (transaction: Transaction) => {
            onClose(transaction.state.status);
        };
        const onSettled = () => {
            submitting.current = false;
        };
        if (kind === 'CLEAR') {
            clear.mutate({ transactionId, on }, { onSuccess, onSettled });
        } else if (kind === 'RECONCILE') {
            reconcile.mutate({ transactionId, on }, { onSuccess, onSettled });
        } else {
            voidTransaction.mutate({ transactionId, on, reason }, { onSuccess, onSettled });
        }
    }

    return (
        <ActionPanel
            title={ACTION_TITLES[kind]}
            headingLevel={2}
            submitLabel={ACTION_LABELS[kind]}
            onSubmit={submit}
            onCancel={() => {
                // Never onClose directly: ActionPanel's Cancel button calls this as a click
                // handler, and passing onClose straight through would read the click event as
                // the newStatus parameter.
                onClose();
            }}
            pending={mutation.isPending}
        >
            <div className={styles.panelFields}>
                {failure === null ? null : <ErrorState ref={errorStateRef} error={failure} />}
                <DateField
                    label="On"
                    required
                    value={on}
                    onChange={(event) => {
                        clearSettledOutcome(mutation);
                        setOn(event.target.value);
                    }}
                />
                {kind === 'VOID' ? (
                    <TextField
                        label="Reason"
                        required
                        value={reason}
                        onChange={(event) => {
                            clearSettledOutcome(mutation);
                            setReason(event.target.value);
                        }}
                    />
                ) : null}
            </div>
        </ActionPanel>
    );
}

/** This row's actions cell: buttons for its legal transitions, or the one open panel. */
function TransactionActionsCell({
    transaction,
    active,
    onOpen,
    onClose,
}: {
    readonly transaction: Transaction;
    readonly active: ActiveAction | undefined;
    readonly onOpen: (transactionId: string, kind: LifecycleKind) => void;
    readonly onClose: (newStatus?: TransactionStatus) => void;
}) {
    return (
        <div id={actionsCellId(transaction.id)}>
            {active?.transactionId === transaction.id ? (
                <LifecycleActionPanel
                    kind={active.kind}
                    transactionId={transaction.id}
                    onClose={onClose}
                />
            ) : (
                <LifecycleButtons
                    status={transaction.state.status}
                    onOpen={(kind) => {
                        onOpen(transaction.id, kind);
                    }}
                />
            )}
        </div>
    );
}

function BackToAccounts() {
    return <Link to={ACCOUNTS_PATH}>Back to Accounts</Link>;
}

function AccountNotFound() {
    return (
        <>
            <h1>Account not found</h1>
            <EmptyState
                message="The ledger holds no account at this address."
                action={<BackToAccounts />}
            />
        </>
    );
}

/** The class that de-emphasises a voided transaction's cells, or none. */
function voidedClass(transaction: Transaction): string | undefined {
    return transaction.state.status === 'VOIDED' ? styles.voided : undefined;
}

/**
 * This account's side of a transaction: every entry posted to it, each exactly as the ledger sent
 * it. Several entries are shown as several amounts; the browser never sums them (ADR-029).
 */
function EntryAmounts({
    transaction,
    accountId,
}: {
    readonly transaction: Transaction;
    readonly accountId: string;
}) {
    const entries = transaction.entries.filter((entry) => entry.account === accountId);
    return (
        <span className={styles.amounts}>
            {entries.map((entry, index) => (
                // An entry has no identity on the wire, and a transaction's entries never reorder.
                <MoneyText
                    key={String(index)}
                    amount={entry.amount.amount}
                    currency={entry.amount.currency}
                />
            ))}
        </span>
    );
}

/**
 * A status badge, followed by the reason when the transaction was voided.
 *
 * `id` and `tabIndex={-1}` let a lifecycle panel that closed after changing this row's status
 * move focus here: a script can focus an off-Tab-order element, but never a plain `<span>`.
 */
function StateCell({ state, id }: { readonly state: TransactionState; readonly id: string }) {
    return (
        <span id={id} tabIndex={-1} className={styles.state}>
            <StateBadge
                status={state.status}
                tones={STATUS_TONES}
                label={STATUS_LABELS[state.status]}
            />
            {state.status === 'VOIDED' ? (
                <span className={styles.reason}>{state.reason}</span>
            ) : null}
        </span>
    );
}

/** The transactions table's columns. The description heads each row. */
function transactionColumns(
    accountId: string,
    active: ActiveAction | undefined,
    onOpen: (transactionId: string, kind: LifecycleKind) => void,
    onClose: (newStatus?: TransactionStatus) => void,
): readonly Column<Transaction>[] {
    return [
        {
            key: 'date',
            header: 'Date',
            align: 'end',
            render: (transaction) => <DateText value={transaction.occurredOn} />,
        },
        {
            key: 'description',
            header: 'Description',
            rowHeader: true,
            render: (transaction) => (
                <span className={voidedClass(transaction)}>{transaction.description}</span>
            ),
        },
        {
            key: 'amount',
            header: 'Amount',
            align: 'end',
            render: (transaction) => (
                <span className={voidedClass(transaction)}>
                    <EntryAmounts transaction={transaction} accountId={accountId} />
                </span>
            ),
        },
        {
            key: 'state',
            header: 'State',
            render: (transaction) => (
                <StateCell state={transaction.state} id={stateCellId(transaction.id)} />
            ),
        },
        {
            key: 'actions',
            header: 'Actions',
            render: (transaction) => (
                <TransactionActionsCell
                    transaction={transaction}
                    active={active}
                    onOpen={onOpen}
                    onClose={onClose}
                />
            ),
        },
    ];
}

/**
 * The account's balance in whichever state its read is in. A failed refetch is shown beside a
 * balance it still holds: TanStack Query keeps a query's data when a later refetch fails, so the
 * failure never replaces what the owner was reading.
 */
function Balance({ balance }: { readonly balance: UseQueryResult<Money> }) {
    const { targetRef, onRetry } = useRetryFocus<HTMLParagraphElement>(
        balance.error,
        balance.isFetching,
        () => {
            void balance.refetch();
        },
    );

    if (balance.isPending) {
        return <p className={styles.placeholder}>Loading balance…</p>;
    }
    const failure = (
        <QueryErrorState error={balance.error} retrying={balance.isFetching} onRetry={onRetry} />
    );
    if (balance.data === undefined) {
        return failure;
    }
    return (
        <>
            {failure}
            <p ref={targetRef} tabIndex={-1} className={styles.balance}>
                <span className={styles.balanceLabel}>Balance</span>
                <MoneyText amount={balance.data.amount} currency={balance.data.currency} />
            </p>
        </>
    );
}

function Transactions({
    accountId,
    transactions,
}: {
    readonly accountId: string;
    readonly transactions: UseQueryResult<Transaction[]>;
}) {
    // At most one row's panel is open at a time, across the whole table. Declared before any
    // early return below, as every hook must be.
    const [active, setActive] = useState<ActiveAction | undefined>(undefined);
    // Which row and action a just-closed panel was for, so focus can return there once the DOM
    // reflects the close; state, not a ref, so it is never read or written during render.
    const [closedAction, setClosedAction] = useState<ClosedAction | undefined>(undefined);
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(
        transactions.error,
        transactions.isFetching,
        () => {
            void transactions.refetch();
        },
    );

    // Runs after a panel closes, whether by cancel or by success - both go through closeAction
    // below, and a failure never does (the panel stays open and keeps its own focus). Prefers
    // the button for the acted-on action, still present after a cancel because the status has
    // not changed; falls back to the row's state cell, which is what actually changed after a
    // successful transition removed that action from the row.
    //
    // Never resets closedAction back to undefined: closeAction always sets a freshly built
    // object, so a later close - even of the same row and kind - is a new reference and reruns
    // this effect regardless of what is still held from the one before.
    useEffect(() => {
        if (closedAction === undefined) {
            return;
        }
        // A cancel changes nothing, so the acted-on action is still legal and its button is what
        // to focus; a success is told apart by the new status it carried (closedAction.newStatus),
        // never by re-reading the DOM - the transactions query's own refetch can still be in
        // flight at this point, so a row's buttons may not yet reflect its new status here.
        const stillOffered =
            closedAction.newStatus === undefined ||
            LEGAL_ACTIONS[closedAction.newStatus].includes(closedAction.kind);
        const target = stillOffered
            ? document
                  .getElementById(actionsCellId(closedAction.transactionId))
                  ?.querySelector<HTMLElement>(`[data-action="${closedAction.kind}"]`)
            : document.getElementById(stateCellId(closedAction.transactionId));
        target?.focus();
    }, [closedAction]);

    function openAction(transactionId: string, kind: LifecycleKind) {
        setActive({ transactionId, kind });
    }

    function closeAction(newStatus?: TransactionStatus) {
        if (active !== undefined) {
            setClosedAction({ ...active, newStatus });
        }
        setActive(undefined);
    }

    if (transactions.isPending) {
        return <p className={styles.placeholder}>Loading transactions…</p>;
    }
    const failure = (
        <QueryErrorState
            error={transactions.error}
            retrying={transactions.isFetching}
            onRetry={onRetry}
        />
    );
    if (transactions.data === undefined) {
        return failure;
    }
    return (
        <div ref={targetRef} tabIndex={-1} className={styles.stack}>
            {failure}
            {transactions.data.length === 0 ? (
                <EmptyState message={NO_TRANSACTIONS_MESSAGE} />
            ) : (
                <DataTable
                    caption="Transactions"
                    columns={transactionColumns(accountId, active, openAction, closeAction)}
                    rows={transactions.data}
                    rowKey={(transaction) => transaction.id}
                />
            )}
        </div>
    );
}

/**
 * One account's page, for an id taken from the URL.
 *
 * The account itself comes from the cached accounts list, because the ledger has no single-item
 * read (ADR-028). The account is not found when the loaded list does not hold it, or when its
 * balance or transactions answer 404 `ACCOUNT_NOT_FOUND`.
 *
 * The document is titled after the account, such as "Checking account", only while the page shows
 * it. While the account loads, or when it is not found, the route's own title stands.
 */
function AccountDetail({ accountId }: { readonly accountId: string }) {
    const accounts = useAccounts();
    const balance = useAccountBalance(accountId);
    const transactions = useAccountTransactions(accountId);
    const { targetRef: accountHeadingRef, onRetry: retryAccounts } =
        useRetryFocus<HTMLHeadingElement>(accounts.error, accounts.isFetching, () => {
            void accounts.refetch();
        });
    const notFound =
        hasProblemCode(balance.error, ACCOUNT_NOT_FOUND) ||
        hasProblemCode(transactions.error, ACCOUNT_NOT_FOUND);
    const account = accounts.data?.find((candidate) => candidate.id === accountId);
    // Called before any early return, as every hook must be.
    useDocumentTitle(notFound || account === undefined ? undefined : `${account.name} account`);

    if (notFound) {
        return <AccountNotFound />;
    }
    const accountsFailure = (
        <QueryErrorState
            error={accounts.error}
            retrying={accounts.isFetching}
            onRetry={retryAccounts}
        />
    );
    if (accounts.data === undefined) {
        return (
            <>
                <p className={styles.back}>
                    <BackToAccounts />
                </p>
                <h1>Account</h1>
                {accounts.isPending ? (
                    <p className={styles.placeholder}>Loading account…</p>
                ) : (
                    accountsFailure
                )}
            </>
        );
    }
    if (account === undefined) {
        return <AccountNotFound />;
    }

    return (
        <>
            <p className={styles.back}>
                <BackToAccounts />
            </p>
            {accountsFailure}
            <header className={styles.header}>
                <h1 ref={accountHeadingRef} tabIndex={-1} className={styles.name}>
                    {account.name}
                </h1>
                <p className={styles.meta}>
                    {ACCOUNT_TYPE_LABELS[account.type]} · {account.currency}
                </p>
                <Balance balance={balance} />
            </header>
            <Transactions accountId={accountId} transactions={transactions} />
        </>
    );
}

/**
 * `/accounts/:accountId`: an account's name, type, currency and balance, then every transaction
 * touching it, in the ledger's order.
 *
 * Each transaction shows this account's entry amounts as received, and its lifecycle state as a
 * badge. A voided one shows its reason and is struck through as well as muted, so it is set apart
 * by more than colour.
 */
export function AccountDetailPage() {
    const { accountId } = useParams();
    return accountId === undefined ? <AccountNotFound /> : <AccountDetail accountId={accountId} />;
}
