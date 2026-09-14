/**
 * Money as the accounting screens hold it: exactly what the ledger sent, never a number.
 *
 * The browser does no arithmetic on money (ADR-029), so there is no behaviour here, only shape.
 */

/** The currencies an account can be opened in, in the order the form offers them. */
export const CURRENCIES = ['ILS', 'USD', 'EUR', 'GBP', 'JPY'] as const;

/** An ISO 4217 code the ledger accepts. */
export type Currency = (typeof CURRENCIES)[number];

/** An amount in one currency. */
export interface Money {
    /** A decimal string exactly as received, such as `"-250.00"`. Never parsed. */
    readonly amount: string;
    readonly currency: Currency;
}
