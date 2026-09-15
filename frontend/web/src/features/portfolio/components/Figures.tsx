import type { ReactNode } from 'react';

import styles from './Figures.module.css';

/**
 * A group of labelled figures, as a description list: each term names the figure beside it, so a
 * screen reader reads the two together.
 *
 * @param props.children - `Figure`s.
 */
export function Figures({ children }: { readonly children: ReactNode }) {
    return <dl className={styles.figures}>{children}</dl>;
}

/**
 * One labelled figure inside `Figures`.
 *
 * @param props.term - what the figure is, such as "Market value".
 * @param props.children - the figure itself, such as a `MoneyText`.
 */
export function Figure({
    term,
    children,
}: {
    readonly term: string;
    readonly children: ReactNode;
}) {
    return (
        <div className={styles.figure}>
            <dt className={styles.term}>{term}</dt>
            <dd className={styles.value}>{children}</dd>
        </div>
    );
}
