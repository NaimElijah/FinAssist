import styles from './StatusLine.module.css';

/** What the line says now. */
export interface StatusLineProps {
    /** One short sentence, such as "Entry 3 added."; empty while there is nothing to report. */
    readonly message: string;
}

/**
 * A polite status line reporting a change the owner caused but focus does not reveal, such as a
 * form row added or a selection cleared (WCAG 4.1.3).
 *
 * It stays on the page while empty: a live region announces a change to what it holds only once
 * it is already there. Setting the message it already holds changes nothing, so nothing is
 * announced; a caller that can report the same sentence twice empties it in between, on whatever
 * makes the earlier report stale.
 *
 * @param props - see `StatusLineProps`.
 */
export function StatusLine({ message }: StatusLineProps) {
    return (
        <p role="status" className={styles.line}>
            {message}
        </p>
    );
}
