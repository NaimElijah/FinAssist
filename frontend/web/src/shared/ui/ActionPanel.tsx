import { type ReactNode, useEffect, useId, useRef } from 'react';

import styles from './ActionPanel.module.css';
import { Button } from './Button';
import { StatusLine } from './StatusLine';

/** Matches the controls a person fills in, so focus lands on a field rather than a button. */
const FIELD_SELECTOR = 'input, select, textarea';

/**
 * Announced while the panel's request is in flight: disabled buttons and `aria-busy` alone tell a
 * screen reader nothing, and the page also disables every other row's action meanwhile.
 */
const PENDING_MESSAGE = 'Sending…';

/** A panel's title level: below the page's h1, and no deeper than an h4 in a screen this size. */
export type ActionPanelHeadingLevel = 2 | 3 | 4;

/** The element each heading level renders. */
const HEADING_ELEMENTS = { 2: 'h2', 3: 'h3', 4: 'h4' } as const;

/** The action's title, its fields, and what submitting or cancelling does. */
export interface ActionPanelProps {
    /** Names the panel and the row it acts on, such as "Void transaction: Rent, 3 Sept 2026". */
    readonly title: string;
    /**
     * The title's heading level, so the panel fits the outline of the page around it. Defaults to
     * 3, because a panel usually sits under the page's h1 and a section's h2.
     */
    readonly headingLevel?: ActionPanelHeadingLevel;
    /** The fields the action needs, rendered by the caller, which also holds their state. */
    readonly children: ReactNode;
    /** The submit button's text, naming what happens, such as "Void". */
    readonly submitLabel: string;
    /**
     * Called when the form is submitted, by the button or by Enter in a field. Never called again
     * until a render shows `pending` false, so one action cannot be sent twice. It must start the
     * request that `pending` reports, synchronously, as a TanStack Query `mutate` does; a call that
     * starts nothing leaves the panel ignoring submits until its caller renders it again.
     */
    readonly onSubmit: () => void;
    /**
     * Called by the Cancel button. The caller closes the panel by unmounting it. Never called while
     * `pending`.
     */
    readonly onCancel: () => void;
    /**
     * A request this panel started is in flight: submit and Cancel are disabled until it clears,
     * and the panel's polite status line says it is sending.
     */
    readonly pending: boolean;
}

/**
 * An inline, non-modal form for one lifecycle action, such as "Clear on [date]" or "Void on
 * [date] because [reason]".
 *
 * The caller opens it by mounting it, and on mount it moves focus to its first field. It is a
 * named region rather than a dialog: it sits beside what it acts on and never blocks the rest of
 * the page, and `HTMLDialogElement.showModal`, which a modal would need, is not implemented in
 * jsdom. A `<section>` with an accessible name already has the `region` role, so none is set.
 *
 * @param props - see `ActionPanelProps`.
 */
export function ActionPanel({
    title,
    headingLevel = 3,
    children,
    submitLabel,
    onSubmit,
    onCancel,
    pending,
}: ActionPanelProps) {
    const titleId = useId();
    const regionRef = useRef<HTMLElement>(null);
    const submitting = useRef(false);
    const Heading = HEADING_ELEMENTS[headingLevel];

    useEffect(() => {
        regionRef.current?.querySelector<HTMLElement>(FIELD_SELECTOR)?.focus();
    }, []);

    // Released by any render that shows no request pending, not only by `pending` turning false: a
    // request that fails at once can settle before its pending state ever renders.
    useEffect(() => {
        if (!pending) {
            submitting.current = false;
        }
    });

    return (
        <section ref={regionRef} aria-labelledby={titleId} className={styles.panel}>
            <Heading id={titleId} className={styles.title}>
                {title}
            </Heading>
            <form
                className={styles.form}
                onSubmit={(event) => {
                    event.preventDefault();
                    // The disabled button stops a click, not a submit event from elsewhere, and a
                    // repeated void or clear is permanent in an append-only journal (ADR-028).
                    // `pending` is the last render's, so two submits in one tick both read it
                    // false; the ref is set at once.
                    if (pending || submitting.current) {
                        return;
                    }
                    submitting.current = true;
                    onSubmit();
                }}
            >
                <div className={styles.fields}>{children}</div>
                <div className={styles.actions}>
                    <Button type="submit" pending={pending}>
                        {submitLabel}
                    </Button>
                    {/* Cancelling unmounts the panel that holds the request, and TanStack Query
                        runs a `mutate` call's callbacks only while its observer is mounted, so its
                        answer - a success, or a 504 whose outcome is unknown - would never show. */}
                    <Button variant="secondary" onClick={onCancel} disabled={pending}>
                        Cancel
                    </Button>
                    <StatusLine message={pending ? PENDING_MESSAGE : ''} />
                </div>
            </form>
        </section>
    );
}
