import type { ReactNode } from 'react';

import { hasProblemCode } from '@/shared/api/client';
import { WriteAccessContext } from '@/shared/lib/writeAccess';
import { QueryErrorState } from '@/shared/ui';

import { writeAccessFor } from './access';
import { SessionContext } from './sessionContext';
import { useSession } from './sessionQueries';
import styles from './SessionGate.module.css';
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
 * While the check is in flight, a plain loading paragraph shows - the same pattern every other
 * pending query in this app uses (`AccountsPage`'s "Loading accounts…", for one), rather than a
 * blank screen or a bespoke spinner.
 *
 * It also provides `WriteAccessContext` (ADR-032), computed once from the session's roles through
 * `writeAccessFor`, so every screen reads whether to offer a control that changes data from the
 * same resolved session rather than each recomputing it. Hiding a control there is a UX courtesy
 * only; the server enforces access regardless of what this context says.
 *
 * @param props - see `SessionGateProps`.
 */
export function SessionGate({ children }: SessionGateProps) {
    const session = useSession();

    if (session.status === 'pending') {
        return <p className={styles.loading}>Checking your session…</p>;
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

    return (
        <SessionContext value={session.data}>
            <WriteAccessContext value={writeAccessFor(session.data.roles)}>
                {children}
            </WriteAccessContext>
        </SessionContext>
    );
}
