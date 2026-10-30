/**
 * What a render or route failure may put in a log record, shared by `ErrorBoundary` and
 * `RouteError` so the two boundaries can never disagree on what is safe to log (ADR-019).
 */
import { isRouteErrorResponse } from 'react-router';

import { ApiError } from '@/shared/api/client';
import type { LogFields } from '@/shared/lib/logger';

/**
 * The structured fields logged for a failure caught by an error boundary.
 *
 * An `ApiError`'s message is the problem's detail, which can carry the owner's own words, such as
 * an account name, so only its status and stable code are logged, as client.ts does (ADR-019).
 * A route error response - React Router's own, such as the 404 for a path no route matches - is
 * logged by its HTTP status, which is what distinguishes one from another. Any other `Error` is a
 * bug, and its message is what makes the record actionable. A thrown value that is none of these
 * is logged by its type alone, since its content is unknown.
 *
 * @param error - whatever was thrown.
 * @returns the fields to log, never a problem's `detail`.
 */
export function failureFields(error: unknown): LogFields {
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
