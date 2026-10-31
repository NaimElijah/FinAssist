import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import {
    NavigationType,
    NavLink,
    Outlet,
    useLocation,
    useMatches,
    useNavigationType,
} from 'react-router';

import { DocumentTitleContext } from '@/shared/lib/documentTitle';
import { deepestRouteTitle } from '@/shared/lib/routeHandle';

import styles from './Layout.module.css';
import { documentTitleFor, PRODUCT_NAME } from './productName';
import { PageFocusTargetContext } from './session/pageFocusTarget';
import { UserMenu } from './session/UserMenu';

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
 * One page's claim on the document title. Claims are told apart by identity, not by title, so a
 * page releasing its claim can never clear a newer claim that happens to say the same thing.
 */
interface TitleClaim {
    readonly title: string;
}

/**
 * The page's level-1 heading inside `main`, made focusable by script if its page did not already
 * do so, or `main` itself when the page has none, such as a route's error.
 *
 * Every page opens with one h1 naming it, so focus on it tells a screen reader where it is, where
 * an unnamed main region would say only "main". The attribute is set here rather than on every
 * page's heading: pages own their markup, and a heading that already sets one - a retry's
 * target - keeps its own. React leaves an attribute it never rendered alone.
 */
function pageFocusTarget(main: HTMLElement): HTMLElement {
    const heading = main.querySelector<HTMLElement>('h1');
    if (heading === null) {
        return main;
    }
    if (!heading.hasAttribute('tabindex')) {
        heading.tabIndex = -1;
    }
    return heading;
}

/**
 * The document title: a page's override when one is held, otherwise the deepest route title
 * declared in `handle`, followed by the product name; the product name alone when neither exists.
 */
function documentTitle(
    override: string | undefined,
    matches: readonly { readonly handle: unknown }[],
): string {
    return documentTitleFor(override ?? deepestRouteTitle(matches));
}

/**
 * The frame around every page: a skip link, a header with the product name and the primary
 * navigation, and the main region the current route renders into.
 *
 * `NavLink` marks the open area's link with `aria-current="page"`, which is also what the
 * stylesheet keys the open tab on, so the visual state and the announced state cannot disagree.
 *
 * It keeps two things in step with the route, since a single-page app does neither by itself:
 * - the document title. A page may claim its own through `useDocumentTitle`, which wins while the
 *   page is mounted; otherwise the deepest matched route's `handle` title is used (see
 *   `RouteHandle`). The layout provides `DocumentTitleContext` to every page it renders;
 * - focus. When a person navigates - a link, or back and forward - focus moves to the new page's
 *   h1 (`pageFocusTarget`), so a screen reader announces the page by name and the next Tab starts
 *   inside it rather than on the link that was left behind. A replace is the app's own redirect,
 *   such as `/` to `/accounts`, and leaves focus alone, so on first load a keyboard user's first
 *   Tab still reaches the skip link. A page that swaps its heading for another once its data
 *   loads, such as an account's "Account" for the account's name, takes focus along to the new
 *   one while it still rests nowhere. The same heading is named to `SessionGate`, through
 *   `PageFocusTargetContext`, as where a retried session check that succeeds puts focus.
 */
export function Layout() {
    const matches = useMatches();
    const { pathname } = useLocation();
    const navigationType = useNavigationType();
    const mainRef = useRef<HTMLElement>(null);
    const previousPathname = useRef(pathname);
    const namePageFocusTarget = useContext(PageFocusTargetContext);
    const [titleClaim, setTitleClaim] = useState<TitleClaim | undefined>(undefined);

    // Stable, so a page's title effect runs again only when its own title changes. The newest
    // claim wins, and releasing a claim clears the override only while that claim is still held.
    const claimTitle = useCallback((title: string) => {
        const claim: TitleClaim = { title };
        setTitleClaim(claim);
        return () => {
            setTitleClaim((held) => (held === claim ? undefined : held));
        };
    }, []);

    const title = documentTitle(titleClaim?.title, matches);
    useEffect(() => {
        document.title = title;
    }, [title]);

    useEffect(() => {
        // Comparing with the last pathname, rather than skipping the first run, keeps StrictMode's
        // second development-only effect run from moving focus on first load.
        if (pathname === previousPathname.current) {
            return;
        }
        previousPathname.current = pathname;
        const main = mainRef.current;
        if (main !== null && navigationType !== NavigationType.Replace) {
            pageFocusTarget(main).focus();
        }
    }, [pathname, navigationType]);

    // Runs before SessionGate's own effects in the commit that mounts the layout, which is the
    // commit a retried session check restores the app in, so the heading is named in time.
    useEffect(() => {
        const main = mainRef.current;
        if (main === null) {
            return;
        }
        namePageFocusTarget(pageFocusTarget(main));
        return () => {
            namePageFocusTarget(null);
        };
    }, [namePageFocusTarget]);

    // A heading that held focus and was then replaced leaves focus on the body; the page's new
    // heading takes it over, unless the person has since put focus somewhere themselves.
    useEffect(() => {
        const main = mainRef.current;
        if (main === null) {
            return;
        }
        let heldHeading: Element | null = null;
        const noteFocus = (event: FocusEvent) => {
            const heading = main.querySelector('h1');
            heldHeading = heading !== null && event.target === heading ? heading : null;
        };
        const observer = new MutationObserver(() => {
            if (heldHeading === null || heldHeading.isConnected) {
                return;
            }
            const target = pageFocusTarget(main);
            if (target !== main) {
                target.focus();
            }
        });
        document.addEventListener('focusin', noteFocus);
        observer.observe(main, { childList: true, subtree: true });
        return () => {
            document.removeEventListener('focusin', noteFocus);
            observer.disconnect();
        };
    }, []);

    return (
        <>
            <a href={`#${MAIN_ID}`} className={styles.skipLink}>
                Skip to main content
            </a>
            <header className={styles.header}>
                <div className={styles.headerInner}>
                    <div className={styles.topRow}>
                        <p className={styles.product}>{PRODUCT_NAME}</p>
                        {/* Rendered only under SessionGate (App.tsx), which never renders Layout
                            until a session has already loaded - see UserMenu's own doc comment. */}
                        <UserMenu />
                    </div>
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
            {/* tabIndex -1 lets the skip link and route changes move focus here in every browser. */}
            <main ref={mainRef} id={MAIN_ID} tabIndex={-1} className={styles.main}>
                <DocumentTitleContext value={claimTitle}>
                    <Outlet />
                </DocumentTitleContext>
            </main>
        </>
    );
}
