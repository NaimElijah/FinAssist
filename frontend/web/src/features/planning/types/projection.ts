/**
 * A scenario's projection as the planning screens show it: cash, invested and total holdings at
 * every step boundary of a horizon, in one currency (ADR-012, ADR-016).
 *
 * No arithmetic: every amount is the string the ledger sent (ADR-029). The one exception, a pixel
 * coordinate, lives in `components/chartScale.ts` and nowhere else.
 */
import type { Currency, Money } from './money';
import type { Cadence } from './recurrence';

/** A projection's first and last step boundaries, both included. ISO `YYYY-MM-DD` dates. */
export interface Horizon {
    readonly from: string;
    readonly to: string;
}

/** The holdings at one step boundary. `total` is the ledger's own sum, never recomputed here. */
export interface BalancePoint {
    /** An ISO `YYYY-MM-DD` step boundary. */
    readonly date: string;
    readonly cash: Money;
    readonly invested: Money;
    readonly total: Money;
}

/** A scenario projected across a horizon. */
export interface Projection {
    /** The id of the scenario projected. */
    readonly scenario: string;
    readonly horizon: Horizon;
    readonly step: Cadence;
    readonly currency: Currency;
    /**
     * Every step boundary from `horizon.from` to `horizon.to`, in order. Never empty: the ledger
     * always includes both ends, and `planningApi` refuses a projection without them.
     */
    readonly points: readonly [BalancePoint, ...BalancePoint[]];
    /**
     * The first step boundary at which cash is negative; absent when there is none. Always one of
     * `points`' dates: `planningApi` refuses a projection whose shortfall names no step. A dip that
     * opens and closes inside a single step is never reported (requirements § Planning).
     */
    readonly firstShortfall?: string;
}

/**
 * What a projection or a comparison is computed over: the horizon, the step, and which accounts
 * open the cash and invested lines. Only ids, dates and the step - never an amount (ADR-028).
 */
export interface ProjectionSettings {
    /** An ISO `YYYY-MM-DD` date, before `to`; the ledger refuses anything else with 422. */
    readonly from: string;
    /** An ISO `YYYY-MM-DD` date, a whole number of steps after `from`. */
    readonly to: string;
    readonly step: Cadence;
    /** Account refs opening the cash line, in the order offered. May be empty: cash opens at zero. */
    readonly cash: readonly string[];
    /** Account refs opening the invested line, in the order offered. May be empty. */
    readonly invested: readonly string[];
}

/** What projecting one scenario asks for. */
export interface ProjectionParams extends ProjectionSettings {
    readonly scenarioId: string;
}
