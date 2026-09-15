/**
 * The budgeting feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `budgetKeys` or `categorisationRuleKeys`, so a mutation invalidates
 * exactly what a query reads rather than a key spelled twice.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { defineBudget, listBudgets, reviewBudget } from '../api/budgetingApi';
import type { DefineBudgetInput } from '../types/budget';

/** The prefix every budgeting key starts with. */
const BUDGETS_KEY = ['budgets'] as const;

/**
 * The query keys the budgeting feature reads and invalidates for budgets.
 *
 * Every key starts with `all`, and TanStack Query matches an invalidation by prefix - but nothing
 * here invalidates `all` itself. Defining a budget adds a row to the list; it cannot change any
 * budget's review, so `useDefineBudget` invalidates `list` alone (Ruling 37). Adding a
 * categorisation rule is the opposite: it cannot change the list (a budget's period, currency and
 * limits are unaffected), but can change how any budget's spend categorises, so
 * `useAddCategorisationRule` invalidates every `review` key instead.
 */
export const budgetKeys = {
    /** The prefix of every budgeting key. */
    all: BUDGETS_KEY,
    /** The budgets list. */
    list: [...BUDGETS_KEY, 'list'] as const,
    /** One budget's review. */
    review: (budgetId: string) => [...BUDGETS_KEY, budgetId, 'review'] as const,
};

/**
 * Every budget, in the ledger's order (period start descending).
 *
 * @returns the query for the budgets list. Its error is an `ApiError` or `ApiBodyError` from the
 *   client, or a `TypeError` for a network failure.
 */
export function useBudgets() {
    return useQuery({ queryKey: budgetKeys.list, queryFn: () => listBudgets() });
}

/**
 * One budget's review, derived fresh by the ledger on every call.
 *
 * @param budgetId - the budget's id.
 * @returns the query for that review. A 404 `BUDGET_NOT_FOUND` is not retried.
 */
export function useBudgetReview(budgetId: string) {
    return useQuery({
        queryKey: budgetKeys.review(budgetId),
        queryFn: () => reviewBudget(budgetId),
    });
}

/**
 * Define a budget, then refetch the budgets list and nothing else.
 *
 * The list's refetch is returned from `onSuccess`, so the mutation stays pending until the list
 * holds the new budget: submit stays disabled until then, narrowing ADR-028's double-submit
 * window, and a caller's own `onSuccess` runs once the new row can be found.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useDefineBudget() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: DefineBudgetInput) => defineBudget(input),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: budgetKeys.list }),
    });
}
