/**
 * The session held in TanStack Query's cache (ADR-029), and signing out of it.
 *
 * `sessionKeys.me` is the one factory the rest of the app invalidates by - `queryClient.ts`
 * invalidates it on any other query's 401, and `useSignOut` clears the whole cache outright,
 * which is a stronger guarantee than invalidating this key alone would be.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

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

/**
 * What `useSignOut` hands a component: the sign-out call, which is safe to make twice in a row,
 * whether a request is in flight, and why the last attempt failed.
 */
export interface SignOut {
    /** Sends the sign-out request, unless one from an earlier call has not yet settled. */
    readonly signOut: () => void;
    /** A sign-out request is in flight. */
    readonly isPending: boolean;
    /**
     * Why the last sign-out failed, while the session it tried to end is still open; `null`
     * before any attempt, during one, and once one succeeds. An `ApiError` for a refusal such
     * as 403 `CSRF_TOKEN_INVALID`, a `TypeError` for a network failure, or a `URIError` when a
     * corrupted CSRF cookie stopped the request before it was sent.
     */
    readonly error: Error | null;
    /**
     * How many sign-outs have failed since the component mounted. Each failure gives it a new
     * value, even one following another before anything rendered in between, so a caller can
     * mount its alert afresh, keyed on it, and have the failure announced again.
     */
    readonly failures: number;
}

/**
 * Sign out: end the gateway's session, drop every cached read of the owner's data, and leave for
 * Keycloak's own end-session page.
 *
 * The whole cache is cleared, not just the session key, because everything else this app has
 * cached - accounts, budgets, a holding's valuation - was read under the session that just ended,
 * and none of it should still answer a query after the next sign-in, which may be a different
 * person. A failed sign-out clears nothing: the session those reads were made under is still
 * open, and the caller shows `error` so the owner knows it.
 *
 * @returns see `SignOut`. `signOut` guards against a double press with a `useRef` rather than
 *   this hook's own `isPending`: two clicks can arrive in the same tick, before the pending state
 *   has rendered, and a guard that reads render-time state would let both through (ADR-028's
 *   no-double-submit rule, applied to a button rather than a form).
 */
export function useSignOut(): SignOut {
    const queryClient = useQueryClient();
    const inFlight = useRef(false);
    const [failures, setFailures] = useState(0);
    const mutation = useMutation({
        mutationFn: requestSignOut,
        onSuccess: ({ logoutUrl }) => {
            queryClient.clear();
            browserLocation.assign(logoutUrl);
        },
        onError: () => {
            setFailures((count) => count + 1);
        },
        onSettled: () => {
            inFlight.current = false;
        },
    });

    return {
        isPending: mutation.isPending,
        error: mutation.error,
        failures,
        signOut: () => {
            if (inFlight.current) {
                return;
            }
            inFlight.current = true;
            mutation.mutate();
        },
    };
}
