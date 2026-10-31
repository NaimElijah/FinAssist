import { type ReactNode, useCallback } from 'react';

import { hasProblemCode } from '@/shared/api/client';
import { WriteAccessContext } from '@/shared/lib/writeAccess';
import { LoadingNote, QueryErrorState, useRetryFocus } from '@/shared/ui';

import { StandalonePage } from '../StandalonePage';
import { writeAccessFor } from './access';
import { PageFocusTargetContext } from './pageFocusTarget';
import { SessionContext } from './sessionContext';
import { useSession } from './sessionQueries';
import { SignIn } from './SignIn';

/** ADR-030's problem code for "no session", answered by every one of the three services. */
const UNAUTHENTICATED = 'UNAUTHENTICATED';

/** Heads the screen shown while the session check is in flight. */
const CHECKING_TITLE = 'Checking your session';

/** Heads the screen shown when the session check fails for any reason but "not signed in". */
const OUTAGE_TITLE = 'Service unavailable';

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
 * While the check is in flight, the shared `LoadingNote` announces it - the same note every other
 * pending read in this app shows (`AccountsPage`'s "Loading accounts…", for one), rather than a
 * blank screen or a bespoke spinner.
 *
 * Each of those three screens stands in for the whole app, so each is a `StandalonePage`: a
 * `<main>`, an h1 and a document title of its own. A retry that restores the session moves focus
 * to the heading of the page it brings back (`useRetryFocus`), since the button that held focus
 * goes with the outage screen; the page renders inside the router, so `Layout` names that heading
 * through `PageFocusTargetContext`.
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
    const { targetRef, onRetry } = useRetryFocus<HTMLElement>(
        session.error,
        session.isFetching,
        () => {
            void session.refetch();
        },
    );
    const namePageFocusTarget = useCallback(
        (target: HTMLElement | null) => {
            targetRef.current = target;
        },
        [targetRef],
    );

    if (session.status === 'pending') {
        return (
            <StandalonePage title={CHECKING_TITLE}>
                <LoadingNote message="Checking your session…" />
            </StandalonePage>
        );
    }

    if (session.status === 'error') {
        if (hasProblemCode(session.error, UNAUTHENTICATED)) {
            return <SignIn />;
        }
        return (
            <StandalonePage title={OUTAGE_TITLE}>
                <QueryErrorState
                    error={session.error}
                    retrying={session.isFetching}
                    onRetry={onRetry}
                />
            </StandalonePage>
        );
    }

    return (
        <SessionContext value={session.data}>
            <WriteAccessContext value={writeAccessFor(session.data.roles)}>
                <PageFocusTargetContext value={namePageFocusTarget}>
                    {children}
                </PageFocusTargetContext>
            </WriteAccessContext>
        </SessionContext>
    );
}
