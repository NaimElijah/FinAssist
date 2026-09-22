import { Button } from '@/shared/ui';

import { isReadOnly } from './sessionApi';
import { useSignedInSession } from './sessionContext';
import { useSignOut } from './sessionQueries';
import styles from './UserMenu.module.css';

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
 */
export function UserMenu() {
    const session = useSignedInSession();
    const { signOut, isPending } = useSignOut();

    return (
        <div className={styles.menu}>
            <span className={styles.name}>{session.name}</span>
            {isReadOnly(session) ? <span className={styles.badge}>Read-only</span> : null}
            <Button
                variant="secondary"
                pending={isPending}
                onClick={() => {
                    signOut();
                }}
            >
                Sign out
            </Button>
        </div>
    );
}
