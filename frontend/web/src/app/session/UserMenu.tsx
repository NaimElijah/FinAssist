import { useEffect, useRef } from 'react';

import { Button } from '@/shared/ui';

import { isReadOnly } from './sessionApi';
import { useSignedInSession } from './sessionContext';
import { useSignOut } from './sessionQueries';
import styles from './UserMenu.module.css';

/** Shown while the last sign-out failed: the session it tried to end is still open. */
const SIGN_OUT_FAILED =
    'Sign-out failed. You are still signed in. Try again, or close the browser.';

/**
 * Who is signed in, and the control that signs them out.
 *
 * Reads the session from `SessionContext` rather than fetching it again: `SessionGate` renders
 * `Layout`, which renders this, only once that fetch has already succeeded, so a second query
 * here would only add a redundant request on every mount (see `sessionContext.ts`).
 *
 * The "Read-only" badge tells a viewer why no control that changes data is offered. Hiding those
 * controls is each screen's own job, through `useWriteAccess`; this component only shows who is
 * signed in and lets them leave.
 *
 * A sign-out that fails leaves the session open, so it is announced rather than passed over: the
 * owner who pressed "Sign out" must not walk away believing it worked. The alert is keyed on
 * the failure count, so a second failure mounts it afresh and is announced again even when no
 * render came between the two. The button is disabled while the request runs, which drops focus
 * to the page body, so a failure moves focus back to it, exactly as `useRetryFocus` does for a
 * retry, and only while focus rests on the body: an element that has taken it since holds the
 * more specific answer. The alert itself never takes focus; being an alert is what announces it.
 */
export function UserMenu() {
    const session = useSignedInSession();
    const { signOut, isPending, error, failures } = useSignOut();
    const signOutRef = useRef<HTMLButtonElement>(null);

    useEffect(() => {
        if (error === null) {
            return;
        }
        const focusLost =
            document.activeElement === null || document.activeElement === document.body;
        if (focusLost) {
            signOutRef.current?.focus();
        }
    }, [error]);

    return (
        <div className={styles.menu}>
            <span className={styles.name}>{session.name}</span>
            {isReadOnly(session) ? <span className={styles.badge}>Read-only</span> : null}
            <Button
                ref={signOutRef}
                variant="secondary"
                pending={isPending}
                onClick={() => {
                    signOut();
                }}
            >
                Sign out
            </Button>
            {error === null ? null : (
                <p key={failures} role="alert" className={styles.failure}>
                    {SIGN_OUT_FAILED}
                </p>
            )}
        </div>
    );
}
