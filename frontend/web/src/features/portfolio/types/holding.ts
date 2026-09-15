/**
 * A holding as the portfolio screens show it: what is held, what it cost, and what it is worth.
 *
 * A quantity is a number of units, not money, but it is a decimal all the same, so it stays the
 * string the assistant sent and nothing here does arithmetic on it (ADR-029).
 */
import type { Money } from './money';

/** What is held in one instrument across its lots, and what those lots cost in total (ADR-013). */
export interface Position {
    readonly symbol: string;
    /** A decimal string exactly as received, such as `"10.125"`. Never parsed. */
    readonly quantity: string;
    readonly cost: Money;
}

/** A position valued at the latest usable unit price, and the gain that price implies. */
export interface Valuation extends Position {
    readonly unitPrice: Money;
    readonly marketValue: Money;
    /** Signed exactly as received: negative for a loss. */
    readonly unrealisedGain: Money;
}
