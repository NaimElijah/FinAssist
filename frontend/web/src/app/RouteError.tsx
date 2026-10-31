import { useEffect } from 'react';
import { useMatches, useRouteError } from 'react-router';

import { log } from '@/shared/lib/logger';
import { titleOf } from '@/shared/lib/routeHandle';
import { ErrorState } from '@/shared/ui';

import { failureFields } from './failureFields';
import { StandalonePage } from './StandalonePage';

/** The heading of a failed route that declares no title of its own. */
const UNTITLED_HEADING = 'Page error';

/**
 * What either boundary shows: the error the route threw, and the heading naming the route that
 * failed - the deepest matched route's `handle` title, or "Page error" where none declares one.
 * Logs the failure once through the structured logger, never the console.
 */
function useRouteFailure(): { readonly error: unknown; readonly heading: string } {
    const error = useRouteError();
    const heading =
        useMatches()
            .map((match) => titleOf(match.handle))
            .filter((candidate) => candidate !== undefined)
            .at(-1) ?? UNTITLED_HEADING;

    // An effect rather than a call during render, so a re-render does not log again. StrictMode
    // runs effects twice in development only, so a dev console shows the record twice.
    useEffect(() => {
        log.error('route failed', failureFields(error));
    }, [error]);

    return { error, heading };
}

/**
 * The error boundary React Router renders in place of an area's route that threw.
 *
 * Mounted on each area, so a failure inside one area replaces that area alone and the header and
 * navigation stay usable. It shows the failure with `ErrorState`, which never shows a stack trace.
 *
 * It renders inside `Layout`'s `<main>`, and opens with an h1 naming the route that failed, since
 * the page it replaced would have had one: the layout moves focus to the page's h1 on navigation,
 * and a screen reader then announces where the person is before the failure itself.
 */
export function RouteError() {
    const { error, heading } = useRouteFailure();
    return (
        <>
            <h1>{heading}</h1>
            <ErrorState error={error} />
        </>
    );
}

/**
 * The error boundary of the root route, which catches what no area can: a failure of `Layout`
 * itself, or of anything rendered outside an area.
 *
 * `Layout` went with the failure, and its `<main>`, document title and heading convention with
 * it, so the failure is a `StandalonePage` titled as `RouteError` heads it - the page's own h1,
 * so the screen still has exactly one. Otherwise it is `RouteError`: logged once, shown with
 * `ErrorState`.
 */
export function RootRouteError() {
    const { error, heading } = useRouteFailure();
    return (
        <StandalonePage title={heading}>
            <ErrorState error={error} />
        </StandalonePage>
    );
}
