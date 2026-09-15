import { NavLink, Outlet } from 'react-router';

import styles from './PlanningLayout.module.css';
import { COMPARISON_PATH, FLOWS_PATH, PROJECTION_PATH, SCENARIOS_PATH } from './paths';

/**
 * `/planning`: the area's shared frame - a heading, the secondary nav between its screens, and
 * the screen itself.
 *
 * `NavLink` marks the open screen's link with `aria-current="page"`, the same convention the
 * app's own primary nav uses.
 */
export function PlanningLayout() {
    return (
        <>
            <h1>Planning</h1>
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
