import { type ReactNode, useEffect, useId, useRef } from 'react';

import styles from './ActionPanel.module.css';
import { Button } from './Button';

/** Matches the controls a person fills in, so focus lands on a field rather than a button. */
const FIELD_SELECTOR = 'input, select, textarea';

/** A panel's title level: below the page's h1, and no deeper than an h4 in a screen this size. */
export type ActionPanelHeadingLevel = 2 | 3 | 4;

/** The element each heading level renders. */
const HEADING_ELEMENTS = { 2: 'h2', 3: 'h3', 4: 'h4' } as const;

/** The action's title, its fields, and what submitting or cancelling does. */
export interface ActionPanelProps {
    /** Names the panel, such as "Void transaction". */
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
     * Called when the form is submitted, by the button or by Enter in a field. Never called while
     * `pending`, so one action cannot be sent twice.
     */
    readonly onSubmit: () => void;
    /** Called by the Cancel button. The caller closes the panel by unmounting it. */
    readonly onCancel: () => void;
    /** A request this panel started is in flight: submit is disabled until it clears. */
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
    const Heading = HEADING_ELEMENTS[headingLevel];

    useEffect(() => {
        regionRef.current?.querySelector<HTMLElement>(FIELD_SELECTOR)?.focus();
    }, []);

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
                    if (pending) {
                        return;
                    }
                    onSubmit();
                }}
            >
                <div className={styles.fields}>{children}</div>
                <div className={styles.actions}>
                    <Button type="submit" pending={pending}>
                        {submitLabel}
                    </Button>
                    <Button variant="secondary" onClick={onCancel}>
                        Cancel
                    </Button>
                </div>
            </form>
        </section>
    );
}
