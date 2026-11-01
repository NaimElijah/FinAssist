/**
 * Money as the portfolio screens hold it: exactly what the assistant sent, never a number.
 *
 * The browser does no arithmetic on money (ADR-029), so there is no behaviour here beyond reading
 * a sign. Duplicated from the other features' `types/money.ts` on purpose: a feature imports no
 * other feature, and the assistant's generated `MoneyModel` has the same members as the ledger's
 * `MoneyDto`.
 */

/** The currencies an amount can be in, in the order forms offer them. */
export const CURRENCIES = ['ILS', 'USD', 'EUR', 'GBP', 'JPY'] as const;

/** An ISO 4217 code the assistant accepts. */
export type Currency = (typeof CURRENCIES)[number];

/** An amount in one currency. */
export interface Money {
    /** A decimal string exactly as received, such as `"-865.94"`. Never parsed. */
    readonly amount: string;
    readonly currency: Currency;
}

/** Whether a signed amount is worded as a gain or a loss. */
export type GainOrLoss = 'gain' | 'loss';

/** How the assistant writes a negative decimal: a leading minus, and zero never carries one. */
const NEGATIVE_SIGN = '-';

/**
 * Word a signed amount as a gain or a loss, from its sign character alone.
 *
 * Never by parsing it: the browser does no arithmetic on money (ADR-029), and the leading minus is
 * all the wording needs. Zero carries no sign, so it reads as a gain.
 *
 * @param amount - a decimal string exactly as received.
 * @returns `loss` when it starts with a minus sign, and `gain` otherwise.
 */
export function gainOrLoss(amount: string): GainOrLoss {
    return amount.startsWith(NEGATIVE_SIGN) ? 'loss' : 'gain';
}
