/**
 * Money as the planning screens hold it: exactly what the ledger sent, never a number.
 *
 * The browser does no arithmetic on money (ADR-029), so there is no behaviour here, only shape.
 * Duplicated from `accounting/types/money.ts` on purpose: a feature imports no other feature, and
 * ADR-029 gives the web client no TypeScript `Money` at all - one would owe ADR-018 every money
 * vector - so even this shape stays out of `src/shared`, where it could grow into one.
 */

/** The currencies a planned flow or scenario can be defined in, in the order forms offer them. */
export const CURRENCIES = ['ILS', 'USD', 'EUR', 'GBP', 'JPY'] as const;

/** An ISO 4217 code the ledger accepts. */
export type Currency = (typeof CURRENCIES)[number];

/** An amount in one currency. */
export interface Money {
    /** A decimal string exactly as received, such as `"250.00"`. Never parsed. */
    readonly amount: string;
    readonly currency: Currency;
}
