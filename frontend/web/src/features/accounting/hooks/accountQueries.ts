/**
 * The accounting feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `accountKeys`, so a mutation invalidates exactly what a query reads
 * rather than a key spelled twice.
 */
import {
    type QueryClient,
    useIsMutating,
    useMutation,
    useQuery,
    useQueryClient,
} from '@tanstack/react-query';

import { hasProblemCode } from '@/shared/api/client';

import {
    clearTransaction,
    getBalance,
    ILLEGAL_TRANSITION,
    listAccounts,
    listAccountTransactions,
    openAccount,
    reconcileTransaction,
    recordTransaction,
    voidTransaction,
} from '../api/accountingApi';
import type { OpenAccountInput } from '../types/account';
import type { RecordTransactionInput, Transaction } from '../types/transaction';

/** The prefix every accounting key starts with. */
const ACCOUNTS_KEY = ['accounts'] as const;

/**
 * The query keys the accounting feature reads and invalidates.
 *
 * Every key starts with `all`, and TanStack Query matches an invalidation by prefix - but nothing
 * here invalidates `all` itself. `list` carries an account's id, name, type and currency, none of
 * which a transaction can ever change, so it is invalidated only when an account is opened; a
 * transaction recorded or moved through its lifecycle invalidates `balance(id)` and
 * `transactions(id)` for exactly the accounts it touches, and nothing wider.
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

/**
 * The account ids an entry set names, in entry order - may repeat when one account is posted to
 * twice.
 */
function accountIdsOf(transaction: Transaction): string[] {
    return transaction.entries.map((entry) => entry.account);
}

/**
 * Record a transaction by hand, then refetch the balance and transactions of every account named
 * in its entries, each exactly once even when the same account is posted to twice.
 *
 * Nothing else is invalidated: an account's id, name, type and currency are never touched by
 * recording a transaction, so refetching the list would add a request with nothing to show for it.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useRecordTransaction() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: RecordTransactionInput) => recordTransaction(input),
        onSuccess: (transaction) => invalidateAccounts(queryClient, accountIdsOf(transaction)),
    });
}

/**
 * The mutation key Clear, Reconcile and Void share, so a page can tell that one is in flight. It
 * lives in the mutation cache, apart from every query key in `accountKeys`.
 */
const TRANSACTION_LIFECYCLE_KEY = [...ACCOUNTS_KEY, 'lifecycle'] as const;

/** Whether a transaction lifecycle mutation is in flight, read two ways. */
export interface LifecycleInFlight {
    /** As of the last render, for disabling the controls that would start another. */
    readonly pending: boolean;
    /**
     * Read from the mutation cache at call time, for an event handler: a click can land after
     * `mutate` has started a request but before the render that disables those controls.
     */
    readonly isPendingNow: () => boolean;
}

/**
 * Whether a Clear, Reconcile or Void is in flight anywhere on screen.
 *
 * A page holding one lifecycle panel at a time reads it before replacing the open panel: the panel
 * owns its mutation's `mutate` callbacks, which TanStack Query drops once their observer
 * unmounts, so replacing it mid-flight would lose the outcome - a 504 "outcome unknown" included
 * (ADR-067).
 *
 * @returns the in-flight state, as rendered and as of now.
 */
export function useTransactionLifecycleInFlight(): LifecycleInFlight {
    const queryClient = useQueryClient();
    const pending = useIsMutating({ mutationKey: TRANSACTION_LIFECYCLE_KEY }) > 0;
    return {
        pending,
        isPendingNow: () => queryClient.isMutating({ mutationKey: TRANSACTION_LIFECYCLE_KEY }) > 0,
    };
}

/** One lifecycle mutation's variables: which transaction, and the date the action took effect. */
interface TransitionVariables {
    readonly transactionId: string;
    readonly on: string;
}

/**
 * After a lifecycle action fails, refetch the balance and transactions of the account on screen
 * when the ledger refused it as 409 `ILLEGAL_TRANSITION`: the transaction moved elsewhere since
 * the page read it, and refetch on focus is off, so its row would otherwise keep showing the
 * stale state and offering the action just refused.
 *
 * A refusal carries no transaction, so only the account on screen is refetched; another account
 * the transaction touches refetches its own when its page mounts. Any other failure answered the
 * request as sent and changed nothing, so nothing is refetched.
 *
 * @returns the settled refetches on a 409, so a caller can return it from `onError` and stay
 *   pending until the row reflects the refusal; otherwise `undefined`.
 */
function refreshAfterIllegalTransition(
    queryClient: QueryClient,
    error: unknown,
    accountId: string,
): Promise<unknown> | undefined {
    return hasProblemCode(error, ILLEGAL_TRANSITION)
        ? invalidateAccounts(queryClient, [accountId])
        : undefined;
}

/**
 * Clear a transaction, then refetch the balance and transactions of every account it touches.
 *
 * @param accountId - the account whose page offers the action, refetched on a 409 (see
 *   `refreshAfterIllegalTransition`).
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useClearTransaction(accountId: string) {
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: TRANSACTION_LIFECYCLE_KEY,
        mutationFn: ({ transactionId, on }: TransitionVariables) =>
            clearTransaction(transactionId, on),
        onSuccess: (transaction) => invalidateAccounts(queryClient, accountIdsOf(transaction)),
        onError: (error) => refreshAfterIllegalTransition(queryClient, error, accountId),
    });
}

/**
 * Reconcile a transaction, then refetch the balance and transactions of every account it touches.
 *
 * @param accountId - the account whose page offers the action, refetched on a 409 (see
 *   `refreshAfterIllegalTransition`).
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useReconcileTransaction(accountId: string) {
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: TRANSACTION_LIFECYCLE_KEY,
        mutationFn: ({ transactionId, on }: TransitionVariables) =>
            reconcileTransaction(transactionId, on),
        onSuccess: (transaction) => invalidateAccounts(queryClient, accountIdsOf(transaction)),
        onError: (error) => refreshAfterIllegalTransition(queryClient, error, accountId),
    });
}

/** `useVoidTransaction`'s variables: which transaction, the date, and why. */
interface VoidVariables extends TransitionVariables {
    readonly reason: string;
}

/**
 * Void a transaction, then refetch the balance and transactions of every account it touches.
 *
 * @param accountId - the account whose page offers the action, refetched on a 409 (see
 *   `refreshAfterIllegalTransition`).
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useVoidTransaction(accountId: string) {
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: TRANSACTION_LIFECYCLE_KEY,
        mutationFn: ({ transactionId, on, reason }: VoidVariables) =>
            voidTransaction(transactionId, on, reason),
        onSuccess: (transaction) => invalidateAccounts(queryClient, accountIdsOf(transaction)),
        onError: (error) => refreshAfterIllegalTransition(queryClient, error, accountId),
    });
}
