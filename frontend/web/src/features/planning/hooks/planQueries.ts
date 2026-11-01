/**
 * The planning feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `planKeys`, so a mutation invalidates exactly what a query reads
 * rather than a key spelled twice.
 */
import {
    type QueryClient,
    useIsMutating,
    useMutation,
    useQuery,
    useQueryClient,
} from '@tanstack/react-query';

import {
    cancelFlow,
    commitFlow,
    compareScenarios,
    defineScenario,
    listPlannedFlows,
    listScenarios,
    planFlow,
    projectScenario,
    realiseFlow,
} from '../api/planningApi';
import type { ComparisonParams } from '../types/comparison';
import type { PlanFlowInput } from '../types/flow';
import type { ProjectionParams } from '../types/projection';
import type { DefineScenarioInput } from '../types/scenario';

/** The prefix every planning key starts with. */
const PLANS_KEY = ['plans'] as const;

/** The prefix every projection's key starts with. */
const PROJECTIONS_KEY = [...PLANS_KEY, 'projections'] as const;

/** The prefix every comparison's key starts with. */
const COMPARISONS_KEY = [...PLANS_KEY, 'comparisons'] as const;

/**
 * The query keys the planning feature reads and invalidates.
 *
 * Every key starts with `all`, and TanStack Query matches an invalidation by prefix - but nothing
 * here invalidates `all` itself. `flows`, `scenarios`, `projections` and `comparisons` are
 * siblings, so invalidating one by prefix can never also match another; `projections` is the
 * parent of every `projection(params)` and `comparisons` of every `comparison(params)`, so "every
 * projection" and "every comparison" are plain prefixes.
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
    /** The prefix of every comparison, whatever its scenarios, baseline, horizon or accounts. */
    comparisons: COMPARISONS_KEY,
    /** Scenarios compared against one baseline over one horizon, step and set of accounts. */
    comparison: (params: ComparisonParams) =>
        [
            ...COMPARISONS_KEY,
            params.scenarioIds,
            params.baselineId,
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
 * default, 0, so a projection is refetched whenever a screen mounts it afresh, and a form marks it
 * stale on every submit (`useInvalidateProjection`), so submitting unchanged fields reads it again.
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
 * Scenarios compared against a baseline over one horizon, from the accounts named.
 *
 * Invalidated exactly when a projection is, for the same reasons (see `useProjection`): a realised
 * or cancelled flow stops counting, and accounting's mutations are deliberately not followed
 * across features. A screen mounting it afresh refetches it, and so does every submit of its form
 * (`useInvalidateComparison`).
 *
 * @param params - the scenarios, the baseline, the horizon, the step and the opening accounts;
 *   each distinct set is its own cache entry.
 * @returns the query for the comparison. Its error is an `ApiError` - 400, or 422
 *   `UNKNOWN_SCENARIO` or `RULE_VIOLATED`, none retried - or an `ApiBodyError` from the client, or
 *   a `TypeError` for a network failure.
 */
export function useComparison(params: ComparisonParams) {
    return useQuery({
        queryKey: planKeys.comparison(params),
        queryFn: () => compareScenarios(params),
    });
}

/**
 * A way to mark one projection stale by its exact key, for a form to call as it submits.
 *
 * Submitting the parameters already on screen yields the same key, which TanStack Query would
 * answer from its cache without a request, and refetching on window focus is off
 * (`src/app/queryClient.ts`). Accounting's mutations are deliberately not followed here (ADR-010),
 * so submitting again is how the owner sees what a transaction recorded since has done: a stale
 * projection on screen refetches at once, and one cached from an earlier submit as soon as it is
 * shown again. `exact` leaves every other cached projection alone.
 *
 * @returns a function marking the projection `params` names stale.
 */
export function useInvalidateProjection(): (params: ProjectionParams) => void {
    const queryClient = useQueryClient();
    return (params) => {
        // The promise settles once the refetch does and never rejects: a failure is shown through
        // the query's own error, so there is nothing here to await.
        void queryClient.invalidateQueries({ queryKey: planKeys.projection(params), exact: true });
    };
}

/**
 * A way to mark one comparison stale by its exact key, for a form to call as it submits - for the
 * reasons `useInvalidateProjection` gives.
 *
 * @returns a function marking the comparison `params` names stale.
 */
export function useInvalidateComparison(): (params: ComparisonParams) => void {
    const queryClient = useQueryClient();
    return (params) => {
        // See `useInvalidateProjection`: nothing to await, and a failure shows through the query.
        void queryClient.invalidateQueries({ queryKey: planKeys.comparison(params), exact: true });
    };
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

/**
 * The mutation key Commit, Realise and Cancel share, so a page can tell that one is in flight. It
 * lives in the mutation cache, apart from every query key in `planKeys`.
 */
const FLOW_LIFECYCLE_KEY = [...PLANS_KEY, 'lifecycle'] as const;

/** Whether a flow lifecycle mutation is in flight, read two ways. */
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
 * Whether a Commit, Realise or Cancel is in flight anywhere on screen.
 *
 * A page holding one lifecycle panel at a time reads it before replacing the open panel: the panel
 * owns its mutation's `mutate` callbacks, which TanStack Query drops once their observer
 * unmounts, so replacing it mid-flight would lose the outcome - a 504 "outcome unknown" included
 * (ADR-067).
 *
 * @returns the in-flight state, as rendered and as of now.
 */
export function useFlowLifecycleInFlight(): LifecycleInFlight {
    const queryClient = useQueryClient();
    const pending = useIsMutating({ mutationKey: FLOW_LIFECYCLE_KEY }) > 0;
    return {
        pending,
        isPendingNow: () => queryClient.isMutating({ mutationKey: FLOW_LIFECYCLE_KEY }) > 0,
    };
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
 * projection or comparison exactly as a planned one does, so only `flows` is invalidated.
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useCommitFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: FLOW_LIFECYCLE_KEY,
        mutationFn: ({ flowId, on }: TransitionVariables) => commitFlow(flowId, on),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: planKeys.flows }),
    });
}

/**
 * Refetch the flows list and every cached projection and comparison, for a transition that takes
 * a flow out of what a projection counts.
 *
 * The ledger projects only `PLANNED` and `COMMITTED` flows (`PlannedFlow.countsTowardsProjection`),
 * and a comparison is projections side by side, so realising or cancelling one changes any
 * projection or comparison of a scenario naming it. Which scenarios name it is not known here, so
 * every one is invalidated by its shared prefix. The scenarios list is not: no scenario field
 * changes.
 *
 * @returns the settled refetches, so a caller can return it from `onSuccess` and stay pending
 *   until they land.
 */
function invalidateAfterLeavingProjection(queryClient: QueryClient): Promise<unknown> {
    return Promise.all([
        queryClient.invalidateQueries({ queryKey: planKeys.flows }),
        queryClient.invalidateQueries({ queryKey: planKeys.projections }),
        queryClient.invalidateQueries({ queryKey: planKeys.comparisons }),
    ]);
}

/**
 * Realise a committed flow, then refetch the flows list and every cached projection and
 * comparison - a realised flow no longer counts towards either (see
 * `invalidateAfterLeavingProjection`).
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useRealiseFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: FLOW_LIFECYCLE_KEY,
        mutationFn: ({ flowId, on }: TransitionVariables) => realiseFlow(flowId, on),
        onSuccess: () => invalidateAfterLeavingProjection(queryClient),
    });
}

/** `useCancelFlow`'s variables: which flow, the date, and why. */
interface CancelVariables extends TransitionVariables {
    readonly reason: string;
}

/**
 * Cancel a planned or committed flow, then refetch the flows list and every cached projection and
 * comparison - a cancelled flow no longer counts towards either (see
 * `invalidateAfterLeavingProjection`).
 *
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function useCancelFlow() {
    const queryClient = useQueryClient();
    return useMutation({
        mutationKey: FLOW_LIFECYCLE_KEY,
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
