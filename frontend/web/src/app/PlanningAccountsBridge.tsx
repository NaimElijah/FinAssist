import type { UseQueryResult } from '@tanstack/react-query';
import { Outlet } from 'react-router';

import { type Account, useAccounts } from '@/features/accounting';
import {
    type AccountOption,
    type AccountOptions,
    AccountOptionsContext,
} from '@/features/planning';

/** One of accounting's accounts as a planning account choice. */
function toAccountOption(account: Account): AccountOption {
    return { ref: account.id, label: account.name, currency: account.currency };
}

/**
 * The accounts query's state, in planning's terms: loading until the accounts first arrive,
 * failed when they never did, and loaded - with a later refetch's failure kept beside them -
 * once they have.
 */
function accountOptionsOf(accounts: UseQueryResult<Account[]>): AccountOptions {
    const failure = accounts.isError
        ? {
              error: accounts.error,
              retrying: accounts.isFetching,
              retry: () => {
                  void accounts.refetch();
              },
          }
        : null;
    if (accounts.data === undefined) {
        return failure === null ? { status: 'loading' } : { status: 'failed', failure };
    }
    return { status: 'loaded', options: accounts.data.map(toAccountOption), failure };
}

/**
 * The layout route around planning's routes, providing the account choices planning declares
 * from accounting's accounts.
 *
 * It is the web client's translation between two bounded contexts, and why it lives here: a
 * feature imports no other feature (ADR-010), so planning names what it needs - an
 * `AccountOption` with a ref, a label and a currency - and `src/app`, the one place that sees
 * every feature, adapts accounting's `Account` into it. It mirrors the ledger's `composition`
 * package, where `AccountingOpeningPositions` translates planning's `AccountRef` over accounting's
 * in-port outside both contexts (ADR-022). Planning never sees an accounting type.
 *
 * It reads the accounts list on every planning screen, flows and scenarios included, since it
 * wraps them all; that list is one request, cached, and shared with the accounting screens.
 */
export function PlanningAccountsBridge() {
    const accounts = useAccounts();
    return (
        <AccountOptionsContext value={accountOptionsOf(accounts)}>
            <Outlet />
        </AccountOptionsContext>
    );
}
