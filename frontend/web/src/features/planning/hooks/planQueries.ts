/**
 * The planning feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `planKeys`, so a mutation invalidates exactly what a query reads
 * rather than a key spelled twice.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
    cancelFlow,
    commitFlow,
    defineScenario,
    listPlannedFlows,
    listScenarios,
    planFlow,
    realiseFlow,
} from '../api/planningApi';
import type { PlanFlowInput } from '../types/flow';
import type { DefineScenarioInput } from '../types/scenario';

/** The prefix every planning key starts with. */
const PLANS_KEY = ['plans'] as const;

/**
 * The query keys the planning feature reads and invalidates.
 *
 * Every key starts with `all`, and TanStack Query matches an invalidation by prefix - but nothing
 * here invalidates `all` itself. `flows` and `scenarios` are siblings, so invalidating one by
 * prefix can never also match the other.
 */
export const planKeys = {
    /** The prefix of every planning key. */
    all: PLANS_KEY,
    /** The planned flows list. */
    flows: [...PLANS_KEY, 'flows'] as const,
    /** The scenarios list. */
    scenarios: [...PLANS_KEY, 'scenarios'] as const,
};

/**
 * Every planned flow, in the ledger's order (by start date) - every state, a cancelled flow
 * included.
 *
 * @returns the query for the flows list. Its error is an `ApiError` or `ApiBodyError` from the
 *   client, or a `TypeError` for a network failure.
 */
export function usePlannedFlows() {
    return useQuery({ queryKey: planKeys.flows, queryFn: () => listPlannedFlows() });
}

/**
 * Every scenario, in the ledger's order (by name).
 *
 * @returns the query for the scenarios list. Its error is an `ApiError` or `ApiBodyError` from
 *   the client, or a `TypeError` for a network failure.
 */
export function useScenarios() {
    return useQuery({ queryKey: planKeys.scenarios, queryFn: () => listScenarios() });
}

/**
 * Plan a flow, then refetch the flows list and nothing else.
 *
 * Planning a flow cannot change any existing scenario - a new flow's id names no scenario yet -
 * so only `flows` is invalidated. The list's refetch is returned from `onSuccess`, so the
 * mutation stays pending until the list holds the new flow: submit stays disabled until then.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function usePlanFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: PlanFlowInput) => planFlow(input),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: planKeys.flows }),
    });
}

/** One lifecycle mutation's variables: which flow, and the date the action took effect. */
interface TransitionVariables {
    readonly flowId: string;
    readonly on: string;
}

/**
 * Commit to a planned flow, then refetch the flows list and nothing else.
 *
 * No `ScenarioResponse` field can change when a flow moves state - a scenario names flow ids and
 * a growth rate, neither of which a transition touches - so only `flows` is invalidated.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useCommitFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: ({ flowId, on }: TransitionVariables) => commitFlow(flowId, on),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: planKeys.flows }),
    });
}

/**
 * Realise a committed flow, then refetch the flows list and nothing else.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useRealiseFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: ({ flowId, on }: TransitionVariables) => realiseFlow(flowId, on),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: planKeys.flows }),
    });
}

/** `useCancelFlow`'s variables: which flow, the date, and why. */
interface CancelVariables extends TransitionVariables {
    readonly reason: string;
}

/**
 * Cancel a planned or committed flow, then refetch the flows list and nothing else.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useCancelFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: ({ flowId, on, reason }: CancelVariables) => cancelFlow(flowId, on, reason),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: planKeys.flows }),
    });
}

/**
 * Define a scenario, then refetch the scenarios list and nothing else.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useDefineScenario() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (input: DefineScenarioInput) => defineScenario(input),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: planKeys.scenarios }),
    });
}
