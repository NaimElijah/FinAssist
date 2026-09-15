/**
 * Accounting feature - accounts, entries and balances.
 *
 * Mirrors the `accounting` bounded context in the Java ledger, and uses its words. Everything
 * this feature exposes to the rest of the app is re-exported here; nothing outside reaches
 * past this file, and this feature imports no other feature.
 */
import type { RouteObject } from 'react-router';

import type { RouteHandle } from '@/shared/lib/routeHandle';

import { AccountDetailPage } from './components/AccountDetailPage';
import { AccountsPage } from './components/AccountsPage';
import { ACCOUNT_PATH, ACCOUNTS_PATH, RECORD_TRANSACTION_PATH } from './components/paths';
import { RecordTransactionPage } from './components/RecordTransactionPage';

/**
 * The accounting area's routes, under the base path `/accounts`. src/app mounts them inside the
 * shared layout, which titles the document from each route's `handle`.
 *
 * The static `RECORD_TRANSACTION_PATH` is listed before the dynamic `ACCOUNT_PATH`; react-router
 * ranks a static path above a dynamic one regardless of array order, so `/accounts/transactions/new`
 * always reaches `RecordTransactionPage`, never `AccountDetailPage` with `transactions` read as an
 * id (`RecordTransactionPage.test.tsx` proves it).
 *
 * An account's page is titled "Account" rather than by its name: a `handle` is static, and the
 * name is known only once the accounts list has loaded.
 */
export const accountingRoutes: RouteObject[] = [
    {
        path: ACCOUNTS_PATH,
        Component: AccountsPage,
        handle: { title: 'Accounts' } satisfies RouteHandle,
    },
    {
        path: RECORD_TRANSACTION_PATH,
        Component: RecordTransactionPage,
        handle: { title: 'Record a transaction' } satisfies RouteHandle,
    },
    {
        path: ACCOUNT_PATH,
        Component: AccountDetailPage,
        handle: { title: 'Account' } satisfies RouteHandle,
    },
];

/**
 * Exported for src/app alone, which adapts the accounts into another feature's terms rather than
 * letting that feature import this one (ADR-029).
 */
export { useAccounts } from './hooks/accountQueries';
export type { Account } from './types/account';
