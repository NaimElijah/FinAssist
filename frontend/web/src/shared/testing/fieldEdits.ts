/**
 * Editing one field of a settled form, for the tests that prove every field clears its answer
 * (frontend/web/CLAUDE.md: "Editing any field of a settled form clears its answer").
 *
 * Each field calls `clearSettledOutcome` from its own change handler, so one field's test proves
 * nothing about another's: a form's test walks a list of these, one test per field.
 */
import { fireEvent, screen } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';

/** One field to edit once the form has settled. */
export interface FieldEdit {
    /**
     * Names the field, or the edit, in the test's title: one word, or words joined by
     * underscores, never a space.
     */
    readonly field: string;
    /**
     * Run before the form is submitted, for a field the form shows only once another is set,
     * such as a recurrence's Times. The answer must be on screen beside the field to be cleared.
     */
    readonly arrange?: (user: UserEvent) => Promise<void>;
    /** Changes the field's value. */
    readonly edit: (user: UserEvent) => Promise<void>;
}

/**
 * Sets the value of the field labelled `label` as a date or month picker would; user-event
 * cannot type into one.
 */
export function changeValue(label: string, value: string): Promise<void> {
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
    return Promise.resolve();
}
