import { useEffect } from 'react';
import { useRouteError } from 'react-router';

import { log } from '@/shared/lib/logger';
import { ErrorState } from '@/shared/ui';

import { failureFields } from './failureFields';

/**
 * The error boundary React Router renders in place of a route that threw.
 *
 * Mounted on each area, so a failure inside one area replaces that area alone and the header and
 * navigation stay usable. It logs the failure once through the structured logger, never the
 * console, and shows it with `ErrorState`, which never shows a stack trace.
 */
export function RouteError() {
    const error = useRouteError();

    // An effect rather than a call during render, so a re-render does not log again. StrictMode
    // runs effects twice in development only, so a dev console shows the record twice.
    useEffect(() => {
        log.error('route failed', failureFields(error));
    }, [error]);

    return <ErrorState error={error} />;
}
