import type { ComponentPropsWithoutRef } from 'react';

import { joinClassNames } from './classNames';
import { type FieldLabelling, FieldFrame } from './Field';
import styles from './Field.module.css';

/** One choice in a SelectField: the value submitted, and the words shown for it. */
export interface SelectOption {
    readonly value: string;
    readonly label: string;
}

/**
 * Native select props, less the ones the field owns, plus a label, hint, error and the options.
 *
 * `id` and the two ARIA attributes come from the frame. `children` is replaced by `options`, and
 * `multiple` is excluded, so the value is always a single string.
 */
export type SelectFieldProps = FieldLabelling &
    Omit<
        ComponentPropsWithoutRef<'select'>,
        'id' | 'children' | 'multiple' | 'aria-describedby' | 'aria-invalid'
    > & {
        /** In display order. Include an option with an empty value for "nothing chosen yet". */
        readonly options: readonly SelectOption[];
    };

/**
 * A labelled native select over a fixed list of options.
 *
 * Every other native select prop passes through to the select.
 *
 * @param props - see `SelectFieldProps`.
 */
export function SelectField({
    label,
    hint,
    error,
    options,
    className,
    ...selectProps
}: SelectFieldProps) {
    return (
        <FieldFrame
            label={label}
            hint={hint}
            error={error}
            renderControl={(control) => (
                <select
                    {...selectProps}
                    {...control}
                    className={joinClassNames(styles.control, className)}
                >
                    {options.map((option) => (
                        <option key={option.value} value={option.value}>
                            {option.label}
                        </option>
                    ))}
                </select>
            )}
        />
    );
}
