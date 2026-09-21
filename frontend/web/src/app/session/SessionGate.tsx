import type { ReactNode } from 'react';

import { hasProblemCode } from '@/shared/api/client';
import { QueryErrorState } from '@/shared/ui';

import { SessionContext } from './sessionContext';
import { useSession } from './sessionQueries';
import { SignIn } from './SignIn';

/** ADR-030's problem code for "no session", answered by every one of the three services. */
const UNAUTHENTICATED = 'UNAUTHENTICATED';

export interface SessionGateProps {
    readonly children: ReactNode;
}

/**
 * Gates the whole app on a signed-in session (ADR-030).
 *
 * Rendered above `RouterProvider` (see `App.tsx`), so nothing behind it - no screen, no ledger or
 * assistant call - runs before a session exists: every one of those calls would otherwise answer
 * its own 401 individually, and the app would show the wrong failure for what is really the
 * ordinary signed-out state.
 *
 * A 401 from the session check is that ordinary state and shows `SignIn`; anything else - a
 * network failure, a 5xx - is a real outage and shows a retryable `QueryErrorState`, so an
 * outage is never mistaken for "please sign in" and vice versa. Once the session loads, it is
 * provided once, through `SessionContext`, to every page under it (`UserMenu` included), so
 * nothing re-fetches what this component already resolved.
 *
 * @param props - see `SessionGateProps`.
 */
export function SessionGate({ children }: SessionGateProps) {
    const session = useSession();

    if (session.status === 'pending') {
        return null;
    }

    if (session.status === 'error') {
        if (hasProblemCode(session.error, UNAUTHENTICATED)) {
            return <SignIn />;
        }
        return (
            <QueryErrorState
                error={session.error}
                retrying={session.isFetching}
                onRetry={() => {
                    void session.refetch();
                }}
            />
        );
    }

    return <SessionContext value={session.data}>{children}</SessionContext>;
}
