/**
 * Scenarios compared side by side, as the planning screens show them: each one's holdings at the
 * horizon's end, and how far it diverges from a baseline at every step (ADR-016).
 *
 * No arithmetic: every amount, divergences included, is the signed string the ledger sent
 * (ADR-029).
 */
import type { Currency } from './money';
import type { BalancePoint, Horizon, ProjectionSettings } from './projection';
import type { Cadence } from './recurrence';

/** One scenario in a comparison. */
export interface ComparedScenario {
    readonly id: string;
    readonly name: string;
    /** Its projected holdings at every step boundary. */
    readonly points: readonly BalancePoint[];
    /** Its holdings at the horizon's end. */
    readonly endState: BalancePoint;
    /**
     * Its difference from the baseline at every step boundary - signed amounts, not balances.
     * Empty for the baseline itself.
     */
    readonly divergence: readonly BalancePoint[];
}

/** Two or more scenarios projected over one horizon, one step and one opening position. */
export interface Comparison {
    readonly horizon: Horizon;
    readonly step: Cadence;
    readonly currency: Currency;
    /** The scenario every divergence is measured from: always one of `scenarios`. */
    readonly baseline: ComparedScenario;
    /** Every scenario compared, the baseline included, in the order the request named them. */
    readonly scenarios: readonly ComparedScenario[];
}

/** What comparing scenarios asks for. */
export interface ComparisonParams extends ProjectionSettings {
    /** The scenarios to compare, in the order they are listed; the ledger requires two or more. */
    readonly scenarioIds: readonly string[];
    /** One of `scenarioIds`; the ledger refuses any other with 422. */
    readonly baselineId: string;
}
