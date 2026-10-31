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
 * The title a route's `handle` declares: `undefined` when the handle is absent or declares no
 * string title. The handle is `unknown` because React Router leaves it untyped.
 */
function titleOf(handle: unknown): string | undefined {
    if (typeof handle !== 'object' || handle === null || !('title' in handle)) {
        return undefined;
    }
    return typeof handle.title === 'string' ? handle.title : undefined;
}

/**
 * The title of the deepest matched route that declares one, so a screen's title replaces its
 * area's and a route declaring none, such as a pathless layout or boundary, is passed over.
 *
 * @param matches - the matched routes from the root down, as `useMatches()` returns them.
 * @returns that title, or `undefined` when no match declares a string title.
 */
export function deepestRouteTitle(
    matches: readonly { readonly handle: unknown }[],
): string | undefined {
    return matches
        .map((match) => titleOf(match.handle))
        .filter((title) => title !== undefined)
        .at(-1);
}
