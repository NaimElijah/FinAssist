/**
 * Planning feature - planned flows, scenarios and their projections.
 *
 * Mirrors the `planning` bounded context in the Java ledger: what the owner intends, kept apart
 * from `accounting`, which records what happened, so a plan and a fact are never summed together
 * (ADR-015).
 */
export { planningRoutes } from './routes';

/**
 * Exported for src/app alone, which adapts another feature's accounts into planning's own terms
 * and provides them around the planning routes, rather than letting planning import that feature
 * (ADR-010, ADR-022).
 */
export { AccountOptionsContext } from './hooks/accountOptions';
export type { AccountOption, AccountOptions, AccountOptionsFailure } from './types/accountOption';
