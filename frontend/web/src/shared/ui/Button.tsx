import type { ComponentPropsWithRef } from 'react';

import styles from './Button.module.css';
import { joinClassNames } from './classNames';

/** How much weight an action carries: the main action, a secondary one, or a destructive one. */
export type ButtonVariant = 'primary' | 'secondary' | 'danger';

/** Every native button prop, its `ref` included, plus a variant and a pending flag. */
export interface ButtonProps extends ComponentPropsWithRef<'button'> {
    /** Defaults to `primary`. */
    readonly variant?: ButtonVariant;
    /**
     * A request this button started is still in flight. The button is disabled and `aria-busy`
     * until it clears: ADR-028 has a create disable its submit while pending, because a
     * double-submitted create is permanent in an append-only journal.
     */
    readonly pending?: boolean;
}

/**
 * A button in one of three variants.
 *
 * Native props pass through, `ref` among them, so a caller can move focus back to the button,
 * with one deliberate difference: `type` defaults to `button` rather
 * than the native `submit`, so a button inside a form submits it only when asked to. An
 * accidental submit here is an accidental create.
 *
 * @param props - see `ButtonProps`. `disabled` and `pending` combine; either disables the button.
 */
export function Button({
    variant = 'primary',
    pending = false,
    disabled = false,
    type = 'button',
    className,
    ...rest
}: ButtonProps) {
    return (
        <button
            {...rest}
            type={type}
            className={joinClassNames(styles.button, className)}
            data-variant={variant}
            disabled={disabled || pending}
            aria-busy={pending}
        />
    );
}
