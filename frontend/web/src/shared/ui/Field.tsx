/**
 * The frame the labelled fields share: a label, a required marker when the control is required,
 * an optional hint, the control, and an optional error, wired together by id.
 *
 * Internal to `shared/ui` and not exported from its index. TextField, DateField, MonthField and
 * SelectField differ only in the control they render, so the association logic and its stylesheet
 * live once, here, rather than four times.
 */
import { type ReactNode, useId } from 'react';

import styles from './Field.module.css';

/** What a required field shows beside its label (WCAG 3.3.2). */
const REQUIRED_MARKER = '(required)';

/** The label, hint and error every labelled field takes. */
export interface FieldLabelling {
    /** Names the control. Required: an unlabelled control cannot be used with a screen reader. */
    readonly label: string;
    /** Guidance shown above the control, and read as part of its description. */
    readonly hint?: string | undefined;
    /**
     * The id of guidance shown outside this field that applies to it as well, such as one rule
     * stated once for every row of a form. The control is described by it after its own hint, so
     * the rule is shown and read once per field rather than repeated beside each one.
     */
    readonly sharedHintId?: string | undefined;
    /** A validation message. When present the control is marked invalid and described by it. */
    readonly error?: string | undefined;
    /**
     * The id of a validation message shown outside this field that applies to it as well, such as
     * one message for a pair of fields either of which could be the fix. The control is marked
     * invalid and described by it, so the message is shown and read once rather than per field.
     */
    readonly sharedErrorId?: string | undefined;
}

/** The id and ARIA attributes a framed control must carry. */
export interface FieldControlProps {
    readonly id: string;
    readonly 'aria-describedby'?: string;
    readonly 'aria-invalid'?: true;
}

/** `FieldLabelling`, plus whether the control is required and the control to frame. */
export interface FieldFrameProps extends FieldLabelling {
    /**
     * Whether the form cannot be submitted without this control, shown as a marker beside the
     * label. The field hands its control the same value, so the two never disagree.
     */
    readonly required?: boolean | undefined;
    /** Renders the control, spreading the attributes it is handed onto it. */
    readonly renderControl: (control: FieldControlProps) => ReactNode;
}

/**
 * Lay out a label, required marker, hint, control and error, associated by ids from `useId`.
 *
 * The control is described by the hint, then a shared hint, then the error, then a shared error,
 * in that order - what it takes before what is wrong with it - and carries `aria-invalid` only
 * while it has either error. Two fields with the same label never share an id.
 * The required marker sits outside the label and is hidden from assistive technology: the
 * control's own `required` already announces the state, so the accessible name stays the label
 * alone rather than saying "required" twice.
 *
 * @param props - see `FieldFrameProps`.
 */
export function FieldFrame({
    label,
    required,
    hint,
    sharedHintId,
    error,
    sharedErrorId,
    renderControl,
}: FieldFrameProps) {
    const id = useId();
    const hintId = `${id}-hint`;
    const errorId = `${id}-error`;
    const describedBy = [
        hint === undefined ? '' : hintId,
        sharedHintId ?? '',
        error === undefined ? '' : errorId,
        sharedErrorId ?? '',
    ]
        .filter((part) => part !== '')
        .join(' ');
    const invalid = error !== undefined || sharedErrorId !== undefined;

    const control: FieldControlProps = {
        id,
        ...(describedBy === '' ? {} : { 'aria-describedby': describedBy }),
        ...(invalid ? { 'aria-invalid': true } : {}),
    };

    return (
        <div className={styles.field}>
            <div className={styles.labelRow}>
                <label htmlFor={id} className={styles.label}>
                    {label}
                </label>
                {required === true ? (
                    <span aria-hidden="true" className={styles.required}>
                        {REQUIRED_MARKER}
                    </span>
                ) : null}
            </div>
            {hint === undefined ? null : (
                <p id={hintId} className={styles.hint}>
                    {hint}
                </p>
            )}
            {renderControl(control)}
            {error === undefined ? null : (
                <p id={errorId} className={styles.error}>
                    {error}
                </p>
            )}
        </div>
    );
}
