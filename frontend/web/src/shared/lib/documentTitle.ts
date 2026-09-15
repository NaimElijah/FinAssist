/**
 * A page's own document title, for when its route's `handle` title is not specific enough.
 *
 * A route's `handle` is static (see `RouteHandle`), so it can name an area or a kind of page, such
 * as "Account", but not the record the page shows, such as "Checking account", which is known only
 * once data has loaded. A page that knows more calls `useDocumentTitle`.
 *
 * Precedence: the layout titles the document with the override while one is held, and otherwise
 * with the deepest matched route's `handle` title, each followed by the product name. An override
 * lasts only while the component that set it is mounted, so leaving the page restores the route's
 * title. Feature pages reuse this for their detail views.
 */
import { createContext, useContext, useEffect } from 'react';

/** Holds `title` as the document title's override, and returns the function that releases it. */
export type ClaimDocumentTitle = (title: string) => () => void;

/**
 * Where a page claims the document title. The app's layout provides it, around the routes.
 *
 * The default accepts a claim and ignores it: a page rendered outside the app shell, such as a
 * feature test mounting its routes alone, has no document title to keep, and runs unchanged.
 */
export const DocumentTitleContext = createContext<ClaimDocumentTitle>(() => () => undefined);

/**
 * Title the document after the calling component while it is mounted, overriding the route's
 * `handle` title.
 *
 * @param title - the page's own title, such as "Checking account", without the product name.
 *   `undefined` sets no override, so the route's title stands, as it should while data loads.
 */
export function useDocumentTitle(title: string | undefined): void {
    const claim = useContext(DocumentTitleContext);

    useEffect(() => {
        if (title === undefined) {
            return;
        }
        return claim(title);
    }, [claim, title]);
}
