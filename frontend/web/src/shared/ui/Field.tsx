/**
 * The frame the three labelled fields share: a label, an optional hint, the control, and an
 * optional error, wired together by id.
 *
 * Internal to `shared/ui` and not exported from its index. TextField, DateField and SelectField
 * differ only in the control they render, so the association logic and its stylesheet live once,
 * here, rather than three times.
 */
import { type ReactNode, useId } from 'react';

import styles from './Field.module.css';

/** The label, hint and error every labelled field takes. */
export interface FieldLabelling {
    /** Names the control. Required: an unlabelled control cannot be used with a screen reader. */
    readonly label: string;
    /** Guidance shown above the control, and read as part of its description. */
    readonly hint?: string | undefined;
    /** A validation message. When present the control is marked invalid and described by it. */
    readonly error?: string | undefined;
}

/** The id and ARIA attributes a framed control must carry. */
export interface FieldControlProps {
    readonly id: string;
    readonly 'aria-describedby'?: string;
    readonly 'aria-invalid'?: true;
}

/** `FieldLabelling`, plus the control to frame. */
export interface FieldFrameProps extends FieldLabelling {
    /** Renders the control, spreading the attributes it is handed onto it. */
    readonly renderControl: (control: FieldControlProps) => ReactNode;
}

/**
 * Lay out a label, hint, control and error, associated by ids from `useId`.
 *
 * The control is described by the hint and then the error, in that order, and carries
 * `aria-invalid` only while there is an error. Two fields with the same label never share an id.
 *
 * @param props - see `FieldFrameProps`.
 */
export function FieldFrame({ label, hint, error, renderControl }: FieldFrameProps) {
    const id = useId();
    const hintId = `${id}-hint`;
    const errorId = `${id}-error`;
    const describedBy = [hint === undefined ? '' : hintId, error === undefined ? '' : errorId]
        .filter((part) => part !== '')
        .join(' ');

    const control: FieldControlProps = {
        id,
        ...(describedBy === '' ? {} : { 'aria-describedby': describedBy }),
        ...(error === undefined ? {} : { 'aria-invalid': true }),
    };

    return (
        <div className={styles.field}>
            <label htmlFor={id} className={styles.label}>
                {label}
            </label>
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
