import styles from './StateBadge.module.css';

/** The colour role a status is shown in. The text always carries the meaning; tone reinforces it. */
export type Tone = 'neutral' | 'positive' | 'negative' | 'warning';

/** A status, the tone for every status it could be, and optional words for it. */
export interface StateBadgeProps<Status extends string> {
    readonly status: Status;
    /** A tone for every status, so a status added to the type fails to compile until it has one. */
    readonly tones: Readonly<Record<Status, Tone>>;
    /**
     * The words shown. Defaults to `status` itself; wire statuses are upper case, such as
     * `VOIDED`, so a feature normally passes one.
     */
    readonly label?: string | undefined;
}

/**
 * A lifecycle status as a small labelled badge.
 *
 * The text is the label, so the status is never conveyed by colour alone. The tone is exposed as
 * `data-tone`, which the stylesheet keys on.
 *
 * @param props - see `StateBadgeProps`.
 */
export function StateBadge<Status extends string>({
    status,
    tones,
    label,
}: StateBadgeProps<Status>) {
    return (
        <span className={styles.badge} data-tone={tones[status]}>
            {label ?? status}
        </span>
    );
}
