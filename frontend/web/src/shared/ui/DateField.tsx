import type { ComponentPropsWithoutRef } from 'react';

import { joinClassNames } from './classNames';
import { type FieldLabelling, FieldFrame } from './Field';
import styles from './Field.module.css';

/**
 * Native input props, less the ones the field owns, plus a label, hint and error.
 *
 * `id` and the two ARIA attributes come from the frame, and `type` is always `date`.
 */
export type DateFieldProps = FieldLabelling &
    Omit<ComponentPropsWithoutRef<'input'>, 'id' | 'type' | 'aria-describedby' | 'aria-invalid'>;

/**
 * A labelled native date input.
 *
 * Its `value` is a `YYYY-MM-DD` string, the same shape the wire carries and `DateText` displays,
 * so a date goes from form to request without ever becoming a `Date` and picking up an offset.
 * Every other native input prop passes through to the input; `required` also shows a marker
 * beside the label.
 *
 * @param props - see `DateFieldProps`.
 */
export function DateField({
    label,
    required,
    hint,
    error,
    sharedErrorId,
    className,
    ...inputProps
}: DateFieldProps) {
    return (
        <FieldFrame
            label={label}
            required={required}
            hint={hint}
            error={error}
            sharedErrorId={sharedErrorId}
            renderControl={(control) => (
                <input
                    {...inputProps}
                    {...control}
                    required={required}
                    type="date"
                    className={joinClassNames(styles.control, className)}
                />
            )}
        />
    );
}
