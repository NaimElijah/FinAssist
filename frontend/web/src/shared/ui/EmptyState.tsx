import type { ReactNode } from 'react';

import styles from './EmptyState.module.css';

/** What to say about the absence, and optionally what to do about it. */
export interface EmptyStateProps {
    /** One plain sentence saying what is missing, such as "No accounts yet." */
    readonly message: string;
    /** A control that fills the gap, such as a button opening a create form. */
    readonly action?: ReactNode;
}

/**
 * The place where content would be when there is none.
 *
 * @param props - see `EmptyStateProps`. Without an `action`, only the message renders.
 */
export function EmptyState({ message, action }: EmptyStateProps) {
    return (
        <div className={styles.empty}>
            <p className={styles.message}>{message}</p>
            {action === undefined ? null : <div className={styles.action}>{action}</div>}
        </div>
    );
}
