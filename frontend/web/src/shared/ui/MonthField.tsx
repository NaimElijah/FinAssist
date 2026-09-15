import type { ComponentPropsWithoutRef } from 'react';

import { joinClassNames } from './classNames';
import { type FieldLabelling, FieldFrame } from './Field';
import styles from './Field.module.css';

/**
 * Native input props, less the ones the field owns, plus a label, hint and error.
 *
 * `id` and the two ARIA attributes come from the frame, and `type` is always `month`.
 */
export type MonthFieldProps = FieldLabelling &
    Omit<ComponentPropsWithoutRef<'input'>, 'id' | 'type' | 'aria-describedby' | 'aria-invalid'>;

/**
 * A labelled native month input.
 *
 * Its `value` is a `YYYY-MM` string, the shape a budget's define form maps to a period with
 * `monthInputToPeriod`. Neither `TextField` nor `DateField` passes `type` through - both fix it
 * deliberately - so this is the smallest addition that offers a native month picker without
 * loosening either. Every other native input prop passes through to the input.
 *
 * @param props - see `MonthFieldProps`.
 */
export function MonthField({ label, hint, error, className, ...inputProps }: MonthFieldProps) {
    return (
        <FieldFrame
            label={label}
            hint={hint}
            error={error}
            renderControl={(control) => (
                <input
                    {...inputProps}
                    {...control}
                    type="month"
                    className={joinClassNames(styles.control, className)}
                />
            )}
        />
    );
}
