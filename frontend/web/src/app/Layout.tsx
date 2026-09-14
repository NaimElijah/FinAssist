import { NavLink, Outlet } from 'react-router';

import styles from './Layout.module.css';

/** The main region's id, which the skip link targets. */
const MAIN_ID = 'main-content';

/**
 * The areas the primary navigation lists, in order. Each path is the base path its feature
 * exports routes under; App.test.tsx fails if the two drift apart.
 */
const PRIMARY_AREAS = [
    { label: 'Accounts', to: '/accounts' },
    { label: 'Budgets', to: '/budgets' },
    { label: 'Planning', to: '/planning' },
    { label: 'Portfolio', to: '/portfolio' },
] as const;

/**
 * The frame around every page: a skip link, a header with the product name and the primary
 * navigation, and the main region the current route renders into.
 *
 * `NavLink` marks the open area's link with `aria-current="page"`, which is also what the
 * stylesheet keys the open tab on, so the visual state and the announced state cannot disagree.
 */
export function Layout() {
    return (
        <>
            <a href={`#${MAIN_ID}`} className={styles.skipLink}>
                Skip to main content
            </a>
            <header className={styles.header}>
                <div className={styles.headerInner}>
                    <p className={styles.product}>FinAssist</p>
                    <nav aria-label="Primary">
                        <ul className={styles.tabs}>
                            {PRIMARY_AREAS.map((area) => (
                                <li key={area.to}>
                                    {/* A module lookup is typed string | undefined, which NavLink's
                                        className refuses; the class always exists at runtime. */}
                                    <NavLink to={area.to} className={styles.tab ?? ''}>
                                        {area.label}
                                    </NavLink>
                                </li>
                            ))}
                        </ul>
                    </nav>
                </div>
            </header>
            {/* tabIndex -1 lets the skip link move focus here in every browser, not only scroll. */}
            <main id={MAIN_ID} tabIndex={-1} className={styles.main}>
                <Outlet />
            </main>
        </>
    );
}
