/**
 * A disposal as the portfolio screens price it: what selling units would realise, and the lots
 * that would remain. Pricing one records nothing.
 *
 * Quantities and amounts stay the strings the assistant sent or the owner typed, and nothing here
 * does arithmetic on them (ADR-029).
 */
import type { Money } from './money';

/** What pricing a disposal takes: how many units to sell, and what selling them would bring in. */
export interface DisposalInput {
    /** A decimal string exactly as typed, such as `"4"`. Never parsed. */
    readonly quantity: string;
    readonly proceeds: Money;
}

/** One purchase still held: how many units, at what unit cost, acquired on which date (ADR-013). */
export interface Lot {
    /** An ISO `YYYY-MM-DD` date. */
    readonly acquiredOn: string;
    /** A decimal string exactly as received. Never parsed. */
    readonly quantity: string;
    readonly unitCost: Money;
}

/**
 * What selling units would realise, lots consumed oldest first (ADR-013), and what would remain.
 */
export interface Disposition {
    readonly symbol: string;
    /** A decimal string exactly as received. Never parsed. */
    readonly quantity: string;
    readonly proceeds: Money;
    readonly costOfSale: Money;
    /** Signed exactly as received: negative for a loss. */
    readonly realisedGain: Money;
    /** Oldest first, in the order the assistant sent them. */
    readonly remaining: readonly Lot[];
}
