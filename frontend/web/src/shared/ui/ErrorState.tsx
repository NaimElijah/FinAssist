import type { ReactNode, Ref } from 'react';

import { ApiError, hasProblemCode } from '../api/client';
import styles from './ErrorState.module.css';
import type { FieldLabelLookup } from './fieldLabels';

/**
 * Shown for anything a service did not explain. An internal message or stack trace means nothing
 * to the owner and can leak implementation detail, so neither is ever shown in its place.
 */
const GENERIC_SENTENCE =
    'Something went wrong. Try again, and reload the page if it keeps happening.';

/** ADR-030's problem code for "signed in, but not permitted" - all three services answer it. */
const FORBIDDEN = 'FORBIDDEN';

/**
 * Shown for a `FORBIDDEN` problem in place of the server's own `detail` or `title`, so a viewer
 * is never told which permission it was refused for. `isRetryable` already excludes every 4xx, a
 * 403 included, so no retry is offered without this component naming the status itself.
 */
const FORBIDDEN_SENTENCE = "You don't have permission to see this.";

/** Whatever was thrown or rejected, and optionally what the owner can do about it. */
export interface ErrorStateProps {
    /** Whatever was thrown or rejected. */
    readonly error: unknown;
    /** A control offered with the message, such as a button that retries the request. */
    readonly action?: ReactNode;
    /**
     * The submitting form's labels for the fields its request carries. Without one, or for a path
     * it does not know, a field error is listed by its wire path rather than dropped.
     */
    readonly fieldLabel?: FieldLabelLookup | undefined;
    /**
     * The alert's element, for a screen that moves focus to it - a form whose submission failed
     * with nothing to show on any one field. React 19 hands a function component its `ref` as a
     * prop, so no `forwardRef` is needed.
     */
    readonly ref?: Ref<HTMLDivElement> | undefined;
}

/**
 * An error, rendered as `role="alert"` so it is announced when it appears.
 *
 * For an `ApiError` carrying a problem, shows the problem's `detail`, falling back to its `title`,
 * followed by one line per field error when the problem lists any (ADR-028), each under the
 * form's own label for the field when `fieldLabel` knows it (WCAG 3.3.1). For anything else -
 * an `ApiError` with no problem, a plain `Error`, a thrown string - shows one generic sentence. A
 * `FORBIDDEN` problem (ADR-030) is a third case, shown with `FORBIDDEN_SENTENCE` rather than
 * either the server's own prose or the generic one, so a viewer is never told what it lacked.
 * An `action`, when given, follows the message, so a screen can offer a retry without inventing
 * its own error layout.
 *
 * The alert carries `tabIndex={-1}`: a screen can move focus to it through `ref`, so the next Tab
 * starts from the failure, and it never becomes a Tab stop of its own.
 *
 * @param props - see `ErrorStateProps`. Without an `action`, no control renders.
 */
export function ErrorState({ error, action, fieldLabel, ref }: ErrorStateProps) {
    const problem = error instanceof ApiError ? error.problem : undefined;
    const message = hasProblemCode(error, FORBIDDEN)
        ? FORBIDDEN_SENTENCE
        : (problem?.detail ?? problem?.title ?? GENERIC_SENTENCE);
    const fieldErrors = problem?.errors ?? [];

    return (
        <div ref={ref} role="alert" tabIndex={-1} className={styles.error}>
            <p className={styles.message}>{message}</p>
            {fieldErrors.length === 0 ? null : (
                <ul className={styles.fields}>
                    {fieldErrors.map((fieldError, index) => (
                        // A field can fail more than one rule, so its name alone is not unique.
                        <li key={`${fieldError.field}-${String(index)}`}>
                            <span className={styles.field}>
                                {fieldLabel?.(fieldError.field) ?? fieldError.field}
                            </span>
                            : {fieldError.message}
                        </li>
                    ))}
                </ul>
            )}
            {action === undefined ? null : <div className={styles.action}>{action}</div>}
        </div>
    );
}
