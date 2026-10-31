import { useEffect } from 'react';
import { useMatches, useRouteError } from 'react-router';

import { log } from '@/shared/lib/logger';
import { titleOf } from '@/shared/lib/routeHandle';
import { ErrorState } from '@/shared/ui';

import { failureFields } from './failureFields';

/** The heading of a failed route that declares no title of its own. */
const UNTITLED_HEADING = 'Page error';

/**
 * The error boundary React Router renders in place of a route that threw.
 *
 * Mounted on each area, so a failure inside one area replaces that area alone and the header and
 * navigation stay usable. It logs the failure once through the structured logger, never the
 * console, and shows it with `ErrorState`, which never shows a stack trace.
 *
 * It opens with an h1 naming the route that failed - the deepest matched route's `handle` title,
 * or "Page error" where none declares one - since the page it replaced would have had one: the
 * layout moves focus to the page's h1 on navigation, and a screen reader then announces where the
 * person is before the failure itself.
 */
export function RouteError() {
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

    return (
        <>
            <h1>{heading}</h1>
            <ErrorState error={error} />
        </>
    );
}
