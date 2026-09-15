/**
 * What a route declares in its `handle` for the layout to read.
 *
 * React Router passes a route's `handle` through to `useMatches()` without a type, so this is the
 * one shape the app agrees on. A feature titles each of its routes with
 * `handle: { title: 'Accounts' } satisfies RouteHandle`, and the layout names the document after
 * the deepest matched route that declares a title, so a sub-route's title replaces its area's.
 */
export interface RouteHandle {
    /** Names the page in the browser tab and history, ahead of the product name. */
    readonly title: string;
}

/**
 * The title a route's `handle` declares.
 *
 * @param handle - one match's `handle`, typed `unknown` because React Router leaves it untyped.
 * @returns the title, or `undefined` when the handle is absent or declares no string title.
 */
export function titleOf(handle: unknown): string | undefined {
    if (typeof handle !== 'object' || handle === null || !('title' in handle)) {
        return undefined;
    }
    return typeof handle.title === 'string' ? handle.title : undefined;
}
