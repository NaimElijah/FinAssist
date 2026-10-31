import { useEffect, useState } from 'react';

import styles from './LoadingNote.module.css';

/**
 * How long the region stays empty after it mounts. A live region inserted with its text already
 * in it may be read out by nothing, since the browser can expose the region and its text in one
 * update; waiting longer than a display frame lets the empty region be exposed first, so the
 * message arrives as a change to it. Short enough that nobody sees a gap worth noticing.
 */
const MESSAGE_DELAY_MS = 100;

/** What is being waited for. */
export interface LoadingNoteProps {
    /** One short phrase, such as "Loading accounts…". */
    readonly message: string;
}

/**
 * The note shown while a read or a request is in flight, announced to assistive technology
 * through a `role="status"` region, politely, so it never interrupts (WCAG 4.1.3).
 *
 * The region renders empty and takes its message `MESSAGE_DELAY_MS` after it mounts, so it is
 * already on the page when its text changes. A read answering sooner unmounts it before anything
 * is shown or said.
 *
 * @param props - see `LoadingNoteProps`.
 */
export function LoadingNote({ message }: LoadingNoteProps) {
    const [messageDue, setMessageDue] = useState(false);

    useEffect(() => {
        const timer = setTimeout(() => {
            setMessageDue(true);
        }, MESSAGE_DELAY_MS);
        return () => {
            clearTimeout(timer);
        };
    }, []);

    return (
        <p role="status" className={styles.note}>
            {messageDue ? message : null}
        </p>
    );
}
