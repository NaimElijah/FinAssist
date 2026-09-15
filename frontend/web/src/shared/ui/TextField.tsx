import type { ComponentPropsWithoutRef } from 'react';

import { joinClassNames } from './classNames';
import { type FieldLabelling, FieldFrame } from './Field';
import styles from './Field.module.css';

/**
 * Native input props, less the ones the field owns, plus a label, hint and error.
 *
 * `id` and the two ARIA attributes come from the frame; `type` is fixed, see `TextField`.
 */
export type TextFieldProps = FieldLabelling &
    Omit<ComponentPropsWithoutRef<'input'>, 'id' | 'type' | 'aria-describedby' | 'aria-invalid'>;

/**
 * A labelled single-line text input.
 *
 * Always `type="text"`. An amount is a decimal string (ADR-029), and `type="number"` would hand it
 * to the browser's number parsing; ask for a numeric keyboard with `inputMode="decimal"` instead.
 * Every other native input prop passes through to the input.
 *
 * @param props - see `TextFieldProps`.
 */
export function TextField({ label, hint, error, className, ...inputProps }: TextFieldProps) {
    return (
        <FieldFrame
            label={label}
            hint={hint}
            error={error}
            renderControl={(control) => (
                <input
                    {...inputProps}
                    {...control}
                    type="text"
                    className={joinClassNames(styles.control, className)}
                />
            )}
        />
    );
}
