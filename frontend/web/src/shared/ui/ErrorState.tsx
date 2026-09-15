import type { ReactNode, Ref } from 'react';

import { ApiError } from '../api/client';
import styles from './ErrorState.module.css';

/**
 * Shown for anything a service did not explain. An internal message or stack trace means nothing
 * to the owner and can leak implementation detail, so neither is ever shown in its place.
 */
const GENERIC_SENTENCE =
    'Something went wrong. Try again, and reload the page if it keeps happening.';

/** Whatever was thrown or rejected, and optionally what the owner can do about it. */
export interface ErrorStateProps {
    /** Whatever was thrown or rejected. */
    readonly error: unknown;
    /** A control offered with the message, such as a button that retries the request. */
    readonly action?: ReactNode;
    /**
     * The alert's element, for a screen that moves focus to it - a form whose submission failed
     * with nothing to show on any one field. React 19 hands a function component its `ref` as a
     * prop, so no `forwardRef` is needed.
     */
    readonly ref?: Ref<HTMLDivElement>;
}

/**
 * An error, rendered as `role="alert"` so it is announced when it appears.
 *
 * For an `ApiError` carrying a problem, shows the problem's `detail`, falling back to its `title`,
 * followed by one line per field error when the problem lists any (ADR-028). For anything else -
 * an `ApiError` with no problem, a plain `Error`, a thrown string - shows one generic sentence.
 * An `action`, when given, follows the message, so a screen can offer a retry without inventing
 * its own error layout.
 *
 * The alert carries `tabIndex={-1}`: a screen can move focus to it through `ref`, so the next Tab
 * starts from the failure, and it never becomes a Tab stop of its own.
 *
 * @param props - see `ErrorStateProps`. Without an `action`, no control renders.
 */
export function ErrorState({ error, action, ref }: ErrorStateProps) {
    const problem = error instanceof ApiError ? error.problem : undefined;
    const message = problem?.detail ?? problem?.title ?? GENERIC_SENTENCE;
    const fieldErrors = problem?.errors ?? [];

    return (
        <div ref={ref} role="alert" tabIndex={-1} className={styles.error}>
            <p className={styles.message}>{message}</p>
            {fieldErrors.length === 0 ? null : (
                <ul className={styles.fields}>
                    {fieldErrors.map((fieldError, index) => (
                        // A field can fail more than one rule, so its name alone is not unique.
                        <li key={`${fieldError.field}-${String(index)}`}>
                            <span className={styles.field}>{fieldError.field}</span>:{' '}
                            {fieldError.message}
                        </li>
                    ))}
                </ul>
            )}
            {action === undefined ? null : <div className={styles.action}>{action}</div>}
        </div>
    );
}
