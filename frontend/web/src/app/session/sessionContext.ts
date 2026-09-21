/**
 * Where the resolved session is read, once `SessionGate` has one.
 *
 * `SessionGate` calls `useSession()` itself and renders its children only once that query has
 * succeeded, so nothing under it needs to fetch the session again to read it - a second
 * `useQuery(sessionKeys.me)` mount would still share the cache, but a fresh mount refetches by
 * default (`staleTime` is 0), which is a real network call this context avoids entirely. This
 * mirrors planning's `AccountOptionsContext`: the component that resolves a read provides it
 * once, and everything under it reads the resolved value rather than resolving it again
 * (ADR-010, ADR-022).
 */
import { createContext, useContext } from 'react';

import type { Session } from './sessionApi';

/**
 * Where the signed-in session is read. `undefined` until `SessionGate` provides one, so a
 * component mounted without it - one rendered outside the app shell entirely, such as a test -
 * fails loudly rather than reading a session that does not exist.
 */
export const SessionContext = createContext<Session | undefined>(undefined);

/**
 * The session `SessionGate` provides around every route.
 *
 * @returns the signed-in session.
 * @throws Error - when no `SessionContext` provider is above the caller.
 */
export function useSignedInSession(): Session {
    const session = useContext(SessionContext);
    if (session === undefined) {
        throw new Error(
            'useSignedInSession needs a SessionContext provider; SessionGate provides one once a session has loaded',
        );
    }
    return session;
}
