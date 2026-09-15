/**
 * The budgeting feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `budgetKeys` or `categorisationRuleKeys`, so a mutation invalidates
 * exactly what a query reads rather than a key spelled twice.
 */
import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
    addCategorisationRule,
    defineBudget,
    listBudgets,
    listCategorisationRules,
    reviewBudget,
} from '../api/budgetingApi';
import type { DefineBudgetInput } from '../types/budget';
import type { CategorisationRule } from '../types/rule';

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

/** The last segment of every key `budgetKeys.review` builds. */
const REVIEW_KEY_SUFFIX = 'review';

/**
 * Whether `queryKey` is some budget's review key, from `budgetKeys.review`.
 *
 * A predicate rather than a literal key, because `useAddCategorisationRule` does not know which
 * budgets exist - it invalidates whichever review queries the cache happens to hold, and none
 * that are not reviews, matching only on `budgetKeys.review`'s own shape.
 */
function isBudgetReviewQuery(queryKey: readonly unknown[]): boolean {
    return queryKey[0] === BUDGETS_KEY[0] && queryKey.at(-1) === REVIEW_KEY_SUFFIX;
}

/** The prefix every categorisation-rule key starts with. */
const CATEGORISATION_RULES_KEY = ['categorisationRules'] as const;

/** The query keys the budgeting feature reads and invalidates for categorisation rules. */
export const categorisationRuleKeys = {
    /** The prefix of every categorisation-rule key. */
    all: CATEGORISATION_RULES_KEY,
    /** The rules list. */
    list: [...CATEGORISATION_RULES_KEY, 'list'] as const,
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

/**
 * Every categorisation rule, in the order it was added.
 *
 * @returns the query for the rules list. Its error is an `ApiError` or `ApiBodyError` from the
 *   client, or a `TypeError` for a network failure.
 */
export function useCategorisationRules() {
    return useQuery({
        queryKey: categorisationRuleKeys.list,
        queryFn: () => listCategorisationRules(),
    });
}

/**
 * Refetch the rules list and every budget review the cache currently holds.
 *
 * A new rule can change how any budget's spend categorises, so every `review` query is
 * invalidated, however many budgets exist; nothing else is, since a rule cannot change a budget's
 * period, currency or limits, and so cannot change the list.
 *
 * @returns the settled refetches, so a caller can return it from `onSuccess` and stay pending
 *   until they land.
 */
function invalidateRulesAndReviews(queryClient: QueryClient): Promise<unknown> {
    return Promise.all([
        queryClient.invalidateQueries({ queryKey: categorisationRuleKeys.list }),
        queryClient.invalidateQueries({
            queryKey: budgetKeys.all,
            predicate: (query) => isBudgetReviewQuery(query.queryKey),
        }),
    ]);
}

/**
 * Add a categorisation rule, then refetch the rules list and every budget review.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useAddCategorisationRule() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: CategorisationRule) => addCategorisationRule(input),
        onSuccess: () => invalidateRulesAndReviews(queryClient),
    });
}
