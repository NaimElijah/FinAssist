import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import { Button, type Column, DataTable, EmptyState, ErrorState, MoneyText } from '@/shared/ui';

import { useAccountBalance, useAccounts } from '../hooks/accountQueries';
import { ACCOUNT_TYPE_LABELS, type Account } from '../types/account';
import styles from './AccountsPage.module.css';
import { OpenAccountForm } from './OpenAccountForm';
import { accountPath } from './paths';

const EMPTY_MESSAGE = 'No accounts yet — open one below';

/** One account's name, linking to its detail, and taking focus when it is the account just opened. */
function AccountLink({
    account,
    focused,
}: {
    readonly account: Account;
    readonly focused: boolean;
}) {
    const ref = useRef<HTMLAnchorElement>(null);

    // Keyed on `focused` rather than run on mount: the new row may render before or after the
    // form reports the account opened, and either order must end with focus here.
    useEffect(() => {
        if (focused) {
            ref.current?.focus();
        }
    }, [focused]);

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
function accountColumns(focusedAccountId: string | undefined): readonly Column<Account>[] {
    return [
        {
            key: 'name',
            header: 'Name',
            rowHeader: true,
            render: (account) => (
                <AccountLink account={account} focused={account.id === focusedAccountId} />
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

/** The accounts list in whichever state its request is in. */
function AccountsList({ focusedAccountId }: { readonly focusedAccountId: string | undefined }) {
    const accounts = useAccounts();

    if (accounts.isPending) {
        return <p className={styles.loading}>Loading accounts…</p>;
    }
    if (accounts.isError) {
        return (
            <ErrorState
                error={accounts.error}
                action={
                    <Button
                        variant="secondary"
                        pending={accounts.isFetching}
                        onClick={() => {
                            void accounts.refetch();
                        }}
                    >
                        Try again
                    </Button>
                }
            />
        );
    }
    if (accounts.data.length === 0) {
        return <EmptyState message={EMPTY_MESSAGE} />;
    }
    return (
        <DataTable
            caption="Accounts"
            captionHidden
            columns={accountColumns(focusedAccountId)}
            rows={accounts.data}
            rowKey={(account) => account.id}
        />
    );
}

/**
 * `/accounts`: every account with its type, currency and derived balance, in the ledger's order,
 * and the form that opens another.
 *
 * Opening an account resets the form and moves focus to the new account's link in the list. That
 * is the one success signal: the link names the account, and focus there also rescues keyboard
 * focus from the submit button, which is disabled while the request is pending.
 */
export function AccountsPage() {
    const [openedAccountId, setOpenedAccountId] = useState<string | undefined>(undefined);

    return (
        <>
            <h1>Accounts</h1>
            <AccountsList focusedAccountId={openedAccountId} />
            <OpenAccountForm
                onOpened={(account) => {
                    setOpenedAccountId(account.id);
                }}
            />
        </>
    );
}
