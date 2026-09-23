/**
 * The session held in TanStack Query's cache (ADR-029), and signing out of it.
 *
 * `sessionKeys.me` is the one factory the rest of the app invalidates by - `queryClient.ts`
 * invalidates it on any other query's 401, and `useSignOut` clears the whole cache outright,
 * which is a stronger guarantee than invalidating this key alone would be.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';

import { browserLocation } from './browserLocation';
import { readSession, signOut as requestSignOut } from './sessionApi';

/** The query key `SessionGate` reads and `queryClient.ts` invalidates on another query's 401. */
export const sessionKeys = {
    me: ['session', 'me'] as const,
};

/**
 * Who is signed in.
 *
 * @returns the query for the session. Its error is an `ApiError` - 401 `UNAUTHENTICATED` is the
 *   ordinary signed-out state, not a failure - or an `ApiBodyError`, or a `TypeError` for a
 *   network failure.
 */
export function useSession() {
    return useQuery({ queryKey: sessionKeys.me, queryFn: readSession });
}

/** Ends the session: a function safe to call twice in a row, and whether that is in flight. */
export interface SignOut {
    /** Sends the sign-out request, unless one from an earlier call has not yet settled. */
    readonly signOut: () => void;
    /** A sign-out request is in flight. */
    readonly isPending: boolean;
}

/**
 * Sign out: end the gateway's session, drop every cached read of the owner's data, and leave for
 * Keycloak's own end-session page.
 *
 * The whole cache is cleared, not just the session key, because everything else this app has
 * cached - accounts, budgets, a holding's valuation - was read under the session that just ended,
 * and none of it should still answer a query after the next sign-in, which may be a different
 * person.
 *
 * @returns see `SignOut`. `signOut` guards against a double press with a `useRef` rather than
 *   this hook's own `isPending`: two clicks can arrive in the same tick, before the pending state
 *   has rendered, and a guard that reads render-time state would let both through (ADR-028's
 *   no-double-submit rule, applied to a button rather than a form).
 */
export function useSignOut(): SignOut {
    const queryClient = useQueryClient();
    const inFlight = useRef(false);
    const mutation = useMutation({
        mutationFn: requestSignOut,
        onSuccess: ({ logoutUrl }) => {
            queryClient.clear();
            browserLocation.assign(logoutUrl);
        },
        onSettled: () => {
            inFlight.current = false;
        },
    });

    return {
        isPending: mutation.isPending,
        signOut: () => {
            if (inFlight.current) {
                return;
            }
            inFlight.current = true;
            mutation.mutate();
        },
    };
}
