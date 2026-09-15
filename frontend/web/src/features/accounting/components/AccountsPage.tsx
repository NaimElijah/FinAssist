import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import {
    type Column,
    DataTable,
    EmptyState,
    MoneyText,
    QueryErrorState,
    useRetryFocus,
} from '@/shared/ui';

import { useAccountBalance, useAccounts } from '../hooks/accountQueries';
import { ACCOUNT_TYPE_LABELS, type Account } from '../types/account';
import styles from './AccountsPage.module.css';
import { OpenAccountForm } from './OpenAccountForm';
import { accountPath, RECORD_TRANSACTION_PATH } from './paths';

const EMPTY_MESSAGE = 'No accounts yet — open one below';

/** One account's name, linking to its detail, and taking focus once when it was just opened. */
function AccountLink({
    account,
    focused,
    onFocused,
}: {
    readonly account: Account;
    readonly focused: boolean;
    /** Called once focus has been applied, so the request to focus can be cleared. */
    readonly onFocused: () => void;
}) {
    const ref = useRef<HTMLAnchorElement>(null);

    // Keyed on `focused` rather than run on mount: the new row may render before or after the
    // form reports the account opened, and either order must end with focus here. Reporting back
    // clears the request, so a later remount of this row cannot take focus a second time.
    useEffect(() => {
        if (focused) {
            ref.current?.focus();
            onFocused();
        }
    }, [focused, onFocused]);

    return (
        <Link ref={ref} to={accountPath(account.id)}>
            {account.name}
        </Link>
    );
}

/** One account's balance, read by its own request, so a failure costs that row alone. */
function AccountBalanceCell({ accountId }: { readonly accountId: string }) {
    const balance = useAccountBalance(accountId);

    if (balance.isPending) {
        return <span className={styles.placeholder}>Loading…</span>;
    }
    if (balance.isError) {
        return <span className={styles.placeholder}>Unavailable</span>;
    }
    return <MoneyText amount={balance.data.amount} currency={balance.data.currency} />;
}

/** The table's columns. The name heads each row, and focus goes to the name just opened. */
function accountColumns(
    focusedAccountId: string | undefined,
    onFocused: () => void,
): readonly Column<Account>[] {
    return [
        {
            key: 'name',
            header: 'Name',
            rowHeader: true,
            render: (account) => (
                <AccountLink
                    account={account}
                    focused={account.id === focusedAccountId}
                    onFocused={onFocused}
                />
            ),
        },
        { key: 'type', header: 'Type', render: (account) => ACCOUNT_TYPE_LABELS[account.type] },
        { key: 'currency', header: 'Currency', render: (account) => account.currency },
        {
            key: 'balance',
            header: 'Balance',
            align: 'end',
            render: (account) => <AccountBalanceCell accountId={account.id} />,
        },
    ];
}

/**
 * The accounts list in whichever state its request is in.
 *
 * Accounts already on screen stay there when a later refetch fails: TanStack Query keeps the data
 * it holds, so the failure and its retry are shown above the rows rather than in their place.
 */
function AccountsList({
    focusedAccountId,
    onFocused,
}: {
    readonly focusedAccountId: string | undefined;
    readonly onFocused: () => void;
}) {
    const accounts = useAccounts();
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(
        accounts.error,
        accounts.isFetching,
        () => {
            void accounts.refetch();
        },
    );

    if (accounts.isPending) {
        return <p className={styles.loading}>Loading accounts…</p>;
    }
    const failure = (
        <QueryErrorState error={accounts.error} retrying={accounts.isFetching} onRetry={onRetry} />
    );
    if (accounts.data === undefined) {
        return failure;
    }
    return (
        <div ref={targetRef} tabIndex={-1} className={styles.stack}>
            {failure}
            {accounts.data.length === 0 ? (
                <EmptyState message={EMPTY_MESSAGE} />
            ) : (
                <DataTable
                    caption="Accounts"
                    captionHidden
                    columns={accountColumns(focusedAccountId, onFocused)}
                    rows={accounts.data}
                    rowKey={(account) => account.id}
                />
            )}
        </div>
    );
}

/**
 * `/accounts`: every account with its type, currency and derived balance, in the ledger's order,
 * and the form that opens another.
 *
 * Opening an account resets the form and moves focus to the new account's link in the list, once.
 * That is the one success signal: the link names the account, and focus there also rescues
 * keyboard focus from the submit button, which is disabled while the request is pending.
 */
export function AccountsPage() {
    const [openedAccountId, setOpenedAccountId] = useState<string | undefined>(undefined);
    // Stable, so the link's focus effect does not run again on every render of the page.
    const clearOpenedAccount = useCallback(() => {
        setOpenedAccountId(undefined);
    }, []);

    return (
        <>
            <h1>Accounts</h1>
            <p className={styles.actions}>
                <Link to={RECORD_TRANSACTION_PATH}>Record transaction</Link>
            </p>
            <AccountsList focusedAccountId={openedAccountId} onFocused={clearOpenedAccount} />
            <OpenAccountForm
                onOpened={(account) => {
                    setOpenedAccountId(account.id);
                }}
            />
        </>
    );
}
