import { ApiError } from '../api/client';
import styles from './ErrorState.module.css';

/**
 * Shown for anything a service did not explain. An internal message or stack trace means nothing
 * to the owner and can leak implementation detail, so neither is ever shown in its place.
 */
const GENERIC_SENTENCE =
    'Something went wrong. Try again, and reload the page if it keeps happening.';

/** Whatever was thrown or rejected. */
export interface ErrorStateProps {
    readonly error: unknown;
}

/**
 * An error, rendered as `role="alert"` so it is announced when it appears.
 *
 * For an `ApiError` carrying a problem, shows the problem's `detail`, falling back to its `title`,
 * followed by one line per field error when the problem lists any (ADR-028). For anything else -
 * an `ApiError` with no problem, a plain `Error`, a thrown string - shows one generic sentence.
 *
 * @param props - see `ErrorStateProps`.
 */
export function ErrorState({ error }: ErrorStateProps) {
    const problem = error instanceof ApiError ? error.problem : undefined;
    const message = problem?.detail ?? problem?.title ?? GENERIC_SENTENCE;
    const fieldErrors = problem?.errors ?? [];

    return (
        <div role="alert" className={styles.error}>
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
        </div>
    );
}
