import { isDecimalString } from '../api/decimal';
import { DISPLAY_LOCALE } from '../lib/locale';
import styles from './MoneyText.module.css';

/** An amount exactly as a service sent it, and its currency. */
export interface MoneyTextProps {
    /** A decimal string exactly as received, such as `"-12.00"`. Never a number. */
    readonly amount: string;
    /** An ISO 4217 currency code, such as `"ILS"`. */
    readonly currency: string;
}

/**
 * Display an amount of money in its currency, in `DISPLAY_LOCALE`.
 *
 * The browser does no arithmetic on money (ADR-029), and this never turns the amount into a
 * number: `Intl.NumberFormat` is handed the string itself and formats it as an exact decimal, so
 * a figure past 2^53 keeps every digit. A negative amount keeps its minus sign, so it reads as
 * negative without relying on colour. Renders `<data value={amount}>`, keeping the exact string
 * machine-readable beside the formatted one.
 *
 * @param props - see `MoneyTextProps`.
 * @throws Error - when `amount` is not a decimal string, rather than letting Intl print "NaN".
 *   The value is left out of the message, because a render error's message can reach the logs.
 * @throws RangeError - from `Intl.NumberFormat`, when `currency` is not a well-formed code.
 */
export function MoneyText({ amount, currency }: MoneyTextProps) {
    if (!isDecimalString(amount)) {
        throw new Error('MoneyText expects a decimal string such as "-12.00"');
    }
    const formatted = new Intl.NumberFormat(DISPLAY_LOCALE, { style: 'currency', currency }).format(
        amount,
    );
    return (
        <data value={amount} className={styles.money}>
            {formatted}
        </data>
    );
}
