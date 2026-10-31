import { NavLink, Outlet, useMatches } from 'react-router';

import { titleOf } from '@/shared/lib/routeHandle';

import styles from './PlanningLayout.module.css';
import { COMPARISON_PATH, FLOWS_PATH, PROJECTION_PATH, SCENARIOS_PATH } from './paths';

/** Heads the area while no screen beneath it declares a title of its own. */
const AREA_TITLE = 'Planning';

/**
 * `/planning`: the area's shared frame - a heading, the secondary nav between its screens, and
 * the screen itself.
 *
 * The heading names the open screen, taken from its route's `handle` title, the same words that
 * title the document. Moving between the screens moves focus to this heading (see the app's
 * `Layout`), so it must say which screen arrived; one "Planning" over all four would announce the
 * same word each time.
 *
 * `NavLink` marks the open screen's link with `aria-current="page"`, the same convention the
 * app's own primary nav uses.
 */
export function PlanningLayout() {
    const screenTitle = useMatches()
        .map((match) => titleOf(match.handle))
        .filter((title) => title !== undefined)
        .at(-1);
    return (
        <>
            <h1>{screenTitle ?? AREA_TITLE}</h1>
            <nav aria-label="Planning" className={styles.nav}>
                <ul className={styles.tabs}>
                    <li>
                        {/* A module lookup is typed string | undefined, which NavLink's className
                            refuses; the class always exists at runtime. */}
                        <NavLink to={FLOWS_PATH} className={styles.tab ?? ''}>
                            Flows
                        </NavLink>
                    </li>
                    <li>
                        <NavLink to={SCENARIOS_PATH} className={styles.tab ?? ''}>
                            Scenarios
                        </NavLink>
                    </li>
                    <li>
                        <NavLink to={PROJECTION_PATH} className={styles.tab ?? ''}>
                            Projection
                        </NavLink>
                    </li>
                    <li>
                        <NavLink to={COMPARISON_PATH} className={styles.tab ?? ''}>
                            Compare
                        </NavLink>
                    </li>
                </ul>
            </nav>
            <Outlet />
        </>
    );
}
