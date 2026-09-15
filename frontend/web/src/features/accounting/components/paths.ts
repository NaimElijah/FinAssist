/**
 * The accounting area's URLs, written once for its routes and its links alike.
 */
import { generatePath } from 'react-router';

/** The accounts list. */
export const ACCOUNTS_PATH = '/accounts';

/**
 * Recording a transaction by hand. Two segments after `/accounts`, so it can never be matched by
 * the single dynamic segment `ACCOUNT_PATH` captures below - the two routes do not compete for a
 * match at all; `RecordTransactionPage.test.tsx` proves it.
 */
export const RECORD_TRANSACTION_PATH = '/accounts/transactions/new';

/** One account's detail. */
export const ACCOUNT_PATH = '/accounts/:accountId';

/**
 * The URL of one account's detail page.
 *
 * @param accountId - the account's id. `generatePath` encodes it as a single path segment.
 * @returns the path, such as `/accounts/0b7d2a4e-…`.
 */
export function accountPath(accountId: string): string {
    return generatePath(ACCOUNT_PATH, { accountId });
}
