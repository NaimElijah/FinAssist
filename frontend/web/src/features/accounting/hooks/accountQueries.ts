/**
 * The accounting feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `accountKeys`, so a mutation invalidates exactly what a query reads
 * rather than a key spelled twice.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { getBalance, listAccounts, openAccount } from '../api/accountingApi';
import type { OpenAccountInput } from '../types/account';

/** The prefix every accounting key starts with, and the key of the accounts list itself. */
const ACCOUNTS_KEY = ['accounts'] as const;

/**
 * The query keys the accounting feature reads and invalidates.
 *
 * Every key starts with `all`, so invalidating `all` refreshes the list and every per-account
 * read beneath it. No key is ever written out anywhere else.
 */
export const accountKeys = {
    /** The accounts list, and the prefix of every per-account key. */
    all: ACCOUNTS_KEY,
    /** One account's balance. */
    balance: (accountId: string) => [...ACCOUNTS_KEY, accountId, 'balance'] as const,
};

/**
 * Every account, in the ledger's order (by name).
 *
 * @returns the query for the accounts list. Its error is an `ApiError` or `ApiBodyError` from the
 *   client, or a `TypeError` for a network failure.
 */
export function useAccounts() {
    return useQuery({ queryKey: accountKeys.all, queryFn: () => listAccounts() });
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
 * Open an account, then refresh every accounting query.
 *
 * The invalidation is returned from `onSuccess`, so the mutation stays pending until the list
 * has been refetched. Submit therefore stays disabled until the new account is on screen, which
 * is as close as the client gets to closing ADR-028's double-submit window, and a caller's own
 * `onSuccess` runs once the new row can be found.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useOpenAccount() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: OpenAccountInput) => openAccount(input),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: accountKeys.all }),
    });
}
