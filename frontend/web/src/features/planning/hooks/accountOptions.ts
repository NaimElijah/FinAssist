/**
 * Planning's port for the accounts a projection can open from: a context planning declares, and
 * `src/app` provides from accounting's accounts (ADR-010, ADR-022).
 *
 * A feature imports no other feature, so planning cannot read accounting's hooks. It names what it
 * needs, in its own words, and leaves the translation to the one place that sees both.
 */
import { createContext, useContext } from 'react';

import type { AccountOptions } from '../types/accountOption';

/**
 * Where planning reads its account choices. `undefined` until a provider supplies a value, so a
 * screen mounted without one fails loudly rather than offering no accounts silently.
 */
export const AccountOptionsContext = createContext<AccountOptions | undefined>(undefined);

/**
 * The account choices `src/app` provides around the planning routes.
 *
 * @returns the provided choices: still loading, failed, or loaded.
 * @throws Error - when no `AccountOptionsContext` provider is above the caller.
 */
export function useAccountOptions(): AccountOptions {
    const accountOptions = useContext(AccountOptionsContext);
    if (accountOptions === undefined) {
        throw new Error(
            'useAccountOptions needs an AccountOptionsContext provider; src/app provides one around the planning routes',
        );
    }
    return accountOptions;
}
