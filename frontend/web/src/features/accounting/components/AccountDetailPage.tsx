import type { UseQueryResult } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';

import { ApiError } from '@/shared/api/client';
import {
    Button,
    type Column,
    DataTable,
    DateText,
    EmptyState,
    ErrorState,
    MoneyText,
    StateBadge,
    type Tone,
} from '@/shared/ui';

import { ACCOUNT_NOT_FOUND } from '../api/accountingApi';
import { useAccountBalance, useAccounts, useAccountTransactions } from '../hooks/accountQueries';
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

/** Whether a read failed because the ledger holds no account with the id in its path. */
function isAccountNotFound(error: Error | null): boolean {
    return error instanceof ApiError && error.problem?.code === ACCOUNT_NOT_FOUND;
}

function BackToAccounts() {
    return <Link to={ACCOUNTS_PATH}>Back to Accounts</Link>;
}

/** A retry for a failed query, busy while the retry is in flight. */
function RetryButton({
    query,
}: {
    readonly query: Pick<UseQueryResult, 'isFetching' | 'refetch'>;
}) {
    return (
        <Button
            variant="secondary"
            pending={query.isFetching}
            onClick={() => {
                void query.refetch();
            }}
        >
            Try again
        </Button>
    );
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

/** A status badge, followed by the reason when the transaction was voided. */
function StateCell({ state }: { readonly state: TransactionState }) {
    return (
        <span className={styles.state}>
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
function transactionColumns(accountId: string): readonly Column<Transaction>[] {
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
            render: (transaction) => <StateCell state={transaction.state} />,
        },
    ];
}

/**
 * A failed read's error state with a retry, or `null` when the read has not failed.
 *
 * Each section below shows it beside data it still holds: TanStack Query keeps a query's data
 * when a later refetch fails, so the failure never replaces what the owner was reading.
 */
function failureOf(query: UseQueryResult) {
    return query.isError ? (
        <ErrorState error={query.error} action={<RetryButton query={query} />} />
    ) : null;
}

function Balance({ balance }: { readonly balance: UseQueryResult<Money> }) {
    if (balance.isPending) {
        return <p className={styles.placeholder}>Loading balance…</p>;
    }
    const failure = failureOf(balance);
    if (balance.data === undefined) {
        return failure;
    }
    return (
        <>
            {failure}
            <p className={styles.balance}>
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
    if (transactions.isPending) {
        return <p className={styles.placeholder}>Loading transactions…</p>;
    }
    const failure = failureOf(transactions);
    if (transactions.data === undefined) {
        return failure;
    }
    return (
        <div className={styles.stack}>
            {failure}
            {transactions.data.length === 0 ? (
                <EmptyState message={NO_TRANSACTIONS_MESSAGE} />
            ) : (
                <DataTable
                    caption="Transactions"
                    columns={transactionColumns(accountId)}
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
 */
function AccountDetail({ accountId }: { readonly accountId: string }) {
    const accounts = useAccounts();
    const balance = useAccountBalance(accountId);
    const transactions = useAccountTransactions(accountId);

    if (isAccountNotFound(balance.error) || isAccountNotFound(transactions.error)) {
        return <AccountNotFound />;
    }
    const accountsFailure = accounts.isPending ? null : failureOf(accounts);
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
    const account = accounts.data.find((candidate) => candidate.id === accountId);
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
                <h1 className={styles.name}>{account.name}</h1>
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
