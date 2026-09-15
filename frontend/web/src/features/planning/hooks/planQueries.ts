/**
 * The planning feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `planKeys`, so a mutation invalidates exactly what a query reads
 * rather than a key spelled twice.
 */
import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
    cancelFlow,
    commitFlow,
    defineScenario,
    listPlannedFlows,
    listScenarios,
    planFlow,
    projectScenario,
    realiseFlow,
} from '../api/planningApi';
import type { PlanFlowInput } from '../types/flow';
import type { ProjectionParams } from '../types/projection';
import type { DefineScenarioInput } from '../types/scenario';

/** The prefix every planning key starts with. */
const PLANS_KEY = ['plans'] as const;

/** The prefix every projection's key starts with. */
const PROJECTIONS_KEY = [...PLANS_KEY, 'projections'] as const;

/**
 * The query keys the planning feature reads and invalidates.
 *
 * Every key starts with `all`, and TanStack Query matches an invalidation by prefix - but nothing
 * here invalidates `all` itself. `flows`, `scenarios` and `projections` are siblings, so
 * invalidating one by prefix can never also match another, and `projections` is the parent of
 * every `projection(params)`, so "every projection" is a plain prefix.
 */
export const planKeys = {
    /** The prefix of every planning key. */
    all: PLANS_KEY,
    /** The planned flows list. */
    flows: [...PLANS_KEY, 'flows'] as const,
    /** The scenarios list. */
    scenarios: [...PLANS_KEY, 'scenarios'] as const,
    /** The prefix of every projection, whatever its scenario, horizon or accounts. */
    projections: PROJECTIONS_KEY,
    /** One scenario projected over one horizon, step and set of opening accounts. */
    projection: (params: ProjectionParams) =>
        [
            ...PROJECTIONS_KEY,
            params.scenarioId,
            params.from,
            params.to,
            params.step,
            params.cash,
            params.invested,
        ] as const,
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
 * One scenario projected over a horizon, from the accounts named.
 *
 * A realised or cancelled flow stops counting towards a projection, so those two mutations
 * invalidate every cached one. Accounting's mutations, which move an opening balance, deliberately
 * do not: a feature imports no other feature (ADR-010). The query client's `staleTime` is the
 * default, 0, so a projection is refetched whenever a screen mounts it afresh.
 *
 * @param params - the scenario, horizon, step and opening accounts; each distinct set is its own
 *   cache entry.
 * @returns the query for the projection. Its error is an `ApiError` - 404 `SCENARIO_NOT_FOUND` or
 *   422 `RULE_VIOLATED`, neither retried - or an `ApiBodyError` from the client, or a `TypeError`
 *   for a network failure.
 */
export function useProjection(params: ProjectionParams) {
    return useQuery({
        queryKey: planKeys.projection(params),
        queryFn: () => projectScenario(params),
    });
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
 * a growth rate, neither of which a transition touches - and a committed flow counts towards a
 * projection exactly as a planned one does, so only `flows` is invalidated.
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
 * Refetch the flows list and every cached projection, for a transition that takes a flow out of
 * what a projection counts.
 *
 * The ledger projects only `PLANNED` and `COMMITTED` flows (`PlannedFlow.countsTowardsProjection`),
 * so realising or cancelling one changes any projection of a scenario naming it. Which scenarios
 * name it is not known here, so every projection is invalidated by its shared prefix. The
 * scenarios list is not: no scenario field changes.
 *
 * @returns the settled refetches, so a caller can return it from `onSuccess` and stay pending
 *   until they land.
 */
function invalidateAfterLeavingProjection(queryClient: QueryClient): Promise<unknown> {
    return Promise.all([
        queryClient.invalidateQueries({ queryKey: planKeys.flows }),
        queryClient.invalidateQueries({ queryKey: planKeys.projections }),
    ]);
}

/**
 * Realise a committed flow, then refetch the flows list and every cached projection - a realised
 * flow no longer counts towards one (see `invalidateAfterLeavingProjection`).
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useRealiseFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: ({ flowId, on }: TransitionVariables) => realiseFlow(flowId, on),
        onSuccess: () => invalidateAfterLeavingProjection(queryClient),
    });
}

/** `useCancelFlow`'s variables: which flow, the date, and why. */
interface CancelVariables extends TransitionVariables {
    readonly reason: string;
}

/**
 * Cancel a planned or committed flow, then refetch the flows list and every cached projection - a
 * cancelled flow no longer counts towards one (see `invalidateAfterLeavingProjection`).
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useCancelFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: ({ flowId, on, reason }: CancelVariables) => cancelFlow(flowId, on, reason),
        onSuccess: () => invalidateAfterLeavingProjection(queryClient),
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
