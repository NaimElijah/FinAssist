/**
 * The accounting feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `accountKeys`, so a mutation invalidates exactly what a query reads
 * rather than a key spelled twice.
 */
import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
    getBalance,
    listAccounts,
    listAccountTransactions,
    openAccount,
    recordTransaction,
} from '../api/accountingApi';
import type { OpenAccountInput } from '../types/account';
import type { RecordTransactionInput, Transaction } from '../types/transaction';

/** The prefix every accounting key starts with. */
const ACCOUNTS_KEY = ['accounts'] as const;

/**
 * The query keys the accounting feature reads and invalidates.
 *
 * Every key starts with `all`, and TanStack Query matches an invalidation by prefix. Invalidating
 * `all` therefore refreshes the list and every balance and transaction list beneath it, which is
 * what a change to entries needs. A change that touches no entry invalidates the one key it
 * affects, such as `list`, so no balance on screen is requested again.
 */
export const accountKeys = {
    /** The prefix of every accounting key. */
    all: ACCOUNTS_KEY,
    /** The accounts list. */
    list: [...ACCOUNTS_KEY, 'list'] as const,
    /** One account's balance. */
    balance: (accountId: string) => [...ACCOUNTS_KEY, accountId, 'balance'] as const,
    /** The transactions touching one account. */
    transactions: (accountId: string) => [...ACCOUNTS_KEY, accountId, 'transactions'] as const,
};

/**
 * Every account, in the ledger's order (by name).
 *
 * @returns the query for the accounts list. Its error is an `ApiError` or `ApiBodyError` from the
 *   client, or a `TypeError` for a network failure.
 */
export function useAccounts() {
    return useQuery({ queryKey: accountKeys.list, queryFn: () => listAccounts() });
}

/**
 * One account's balance, as the ledger derives it.
 *
 * ADR-028 accepts one balance request per account on the accounts screen, so each row reads its
 * own rather than the list carrying balances.
 *
 * @param accountId - the account's id.
 * @returns the query for that balance. A 404 `ACCOUNT_NOT_FOUND` is not retried.
 */
export function useAccountBalance(accountId: string) {
    return useQuery({
        queryKey: accountKeys.balance(accountId),
        queryFn: () => getBalance(accountId),
    });
}

/**
 * The transactions touching one account, in the ledger's order.
 *
 * @param accountId - the account's id.
 * @returns the query for those transactions. A 404 `ACCOUNT_NOT_FOUND` is not retried; an
 *   `ApiBodyError` means a state arrived without a member its variant always carries.
 */
export function useAccountTransactions(accountId: string) {
    return useQuery({
        queryKey: accountKeys.transactions(accountId),
        queryFn: () => listAccountTransactions(accountId),
    });
}

/**
 * Open an account, then refetch the accounts list and nothing else.
 *
 * Opening an account posts no entry, so no balance or transaction list can have changed; only the
 * list gains a row, and the new row reads its own balance when it mounts. The list's refetch is
 * returned from `onSuccess`, so the mutation stays pending until the list holds the new account:
 * submit stays disabled until then, which is as close as the client gets to closing ADR-028's
 * double-submit window, and a caller's own `onSuccess` runs once the new row can be found.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useOpenAccount() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: OpenAccountInput) => openAccount(input),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: accountKeys.list }),
    });
}

/**
 * Refetch the balance and transactions of every account named in `accountIds`, once each.
 *
 * Neither recording a transaction nor moving one through its lifecycle can change an account not
 * named in its entries, so this is the whole of what either invalidates: never `accountKeys.all`,
 * which would also refetch every other account's balance and transaction list on screen.
 *
 * @param accountIds - account ids, possibly repeating one across several entries.
 * @returns the settled refetches, so a caller can return it from `onSuccess` and stay pending
 *   until they land.
 */
function invalidateAccounts(
    queryClient: QueryClient,
    accountIds: readonly string[],
): Promise<unknown> {
    const uniqueIds = [...new Set(accountIds)];
    return Promise.all(
        uniqueIds.flatMap((accountId) => [
            queryClient.invalidateQueries({ queryKey: accountKeys.balance(accountId) }),
            queryClient.invalidateQueries({ queryKey: accountKeys.transactions(accountId) }),
        ]),
    );
}

/** The account ids an entry set names, each once. */
function accountIdsOf(transaction: Transaction): string[] {
    return transaction.entries.map((entry) => entry.account);
}

/**
 * Record a transaction by hand, then refetch the accounts list and the balance and transactions
 * of every account named in its entries.
 *
 * The list is refetched because a transaction can be the first to touch an account only the list
 * already knew about (its row exists, but this is its first entry); every other account's balance
 * and transaction list is untouched, so nothing else is invalidated.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useRecordTransaction() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: RecordTransactionInput) => recordTransaction(input),
        onSuccess: (transaction) =>
            Promise.all([
                invalidateAccounts(queryClient, accountIdsOf(transaction)),
                queryClient.invalidateQueries({ queryKey: accountKeys.list }),
            ]),
    });
}
