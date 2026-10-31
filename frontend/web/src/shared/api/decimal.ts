/**
 * The wire's decimal string, checked where a response arrives (ADR-028, ADR-029).
 *
 * Every decimal - an amount of money, a quantity, a growth rate - travels as a string, never a
 * JSON number, because `JSON.parse` has already rounded a number by the time any code sees it. The
 * generated types promise a string, but a body is unvalidated JSON, so each feature's `api/` passes
 * every decimal member it maps through `decimalString` rather than trusting the type.
 */
import { ApiBodyError } from './client';
import { log } from '../lib/logger';

/**
 * The wire's decimal-string pattern, as `contracts/ledger-v1.openapi.json` and
 * `contracts/assistant-v1.openapi.json` both declare it (ADR-028).
 */
const DECIMAL_STRING = /^-?\d+(\.\d+)?$/;

/**
 * `DECIMAL_STRING` put to a person typing a decimal into a form, kept beside the pattern it states
 * (WCAG 3.3.2). The contract refuses a thousands separator, such as `1,000.00`, which is the
 * mistake worth naming; the example shows the decimal point.
 */
export const DECIMAL_HINT = 'A number with no commas, for example 1250.50';

/**
 * `value` as the decimal string the contract promises, unchanged.
 *
 * It is typed `unknown` because a JSON number would pass the pattern once coerced to text, and the
 * wire carries a string. The value itself is left out of the log line, since it arrived
 * unvalidated.
 *
 * @param value - the member as it arrived in the response body.
 * @param url - the URL the response came from, carried on the error and the log line.
 * @param member - the member's name, such as `amount` or `quantity`, for the error and log line.
 * @returns `value`, when it is a string matching the wire's decimal pattern.
 * @throws ApiBodyError - when `value` is not a string, such as a JSON number, or is a string that
 *   is not a decimal, such as `1,234.50`.
 */
export function decimalString(value: unknown, url: string, member: string): string {
    if (typeof value === 'string' && DECIMAL_STRING.test(value)) {
        return value;
    }
    log.error('response has a decimal the contract does not allow', { url, member });
    throw new ApiBodyError(url, `A response arrived with a ${member} the contract does not allow`);
}
