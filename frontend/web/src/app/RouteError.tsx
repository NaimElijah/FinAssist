import { useEffect } from 'react';
import { isRouteErrorResponse, useRouteError } from 'react-router';

import { ApiError } from '@/shared/api/client';
import { type LogFields, log } from '@/shared/lib/logger';
import { ErrorState } from '@/shared/ui';

/**
 * The structured fields logged for a route failure.
 *
 * An `ApiError`'s message is the problem's detail, which can carry the owner's own words, such as
 * an account name, so only its status and stable code are logged, as client.ts does (ADR-019).
 * A route error response - React Router's own, such as the 404 for a path no route matches - is
 * logged by its HTTP status, which is what distinguishes one from another. Any other `Error` is a
 * bug, and its message is what makes the record actionable. A thrown value that is none of these
 * is logged by its type alone, since its content is unknown.
 */
function failureFields(error: unknown): LogFields {
    if (isRouteErrorResponse(error)) {
        return { status: error.status };
    }
    if (error instanceof ApiError) {
        return {
            status: error.status,
            ...(error.problem === undefined ? {} : { code: error.problem.code }),
        };
    }
    if (error instanceof Error) {
        return { reason: error.message };
    }
    return { reason: typeof error };
}

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
