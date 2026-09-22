/**
 * The gateway's own session endpoints, and the web client's anti-corruption edge for them
 * (ADR-029, ADR-030). The only module allowed to import a generated gateway type.
 *
 * Session lives in `src/app` rather than under `src/features`: no bounded context owns it, and
 * every feature needs it, so a feature-shaped anti-corruption layer would have no home to sit
 * in without violating "a feature imports no other feature".
 */
import { BFF_API, requestJson, sendJson } from '@/shared/api/client';
import type { LogoutResponse, SessionResponse } from '@/shared/api/contracts/gateway/types.gen';

/** The realm role ADR-032 grants only read permissions to. */
const VIEWER_ROLE = 'viewer';

/** The realm role ADR-032 grants read and write permissions to. */
const OWNER_ROLE = 'owner';

/** Who is signed in, in the app's own words: the gateway's `subject`, `name` and realm `roles`. */
export interface Session {
    readonly subject: string;
    readonly name: string;
    readonly roles: readonly string[];
}

/** What ending the session answers: the URL that also ends Keycloak's. */
export interface LogoutOutcome {
    readonly logoutUrl: string;
}

function toSession(wire: SessionResponse): Session {
    return { subject: wire.subject, name: wire.name, roles: wire.roles };
}

/**
 * Whether `session` holds only the read-only role: `viewer` without `owner`.
 *
 * A session carrying both is an owner who also happens to have the viewer role, so the wider
 * permission wins. A session carrying neither is not a viewer and gets no badge, although
 * `writeAccessFor` offers it no control that changes data either.
 *
 * @param session - the signed-in session.
 */
export function isReadOnly(session: Session): boolean {
    return session.roles.includes(VIEWER_ROLE) && !session.roles.includes(OWNER_ROLE);
}

const ME_URL = `${BFF_API}/me`;
const LOGOUT_URL = `${BFF_API}/logout`;

/**
 * Who is signed in.
 *
 * @returns the session.
 * @throws ApiError - 401 `UNAUTHENTICATED` when nobody is signed in - the ordinary signed-out
 *   state a fresh visit starts in, not a failure.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function readSession(): Promise<Session> {
    const wire = await requestJson<SessionResponse>(ME_URL);
    return toSession(wire);
}

/**
 * End the gateway's session.
 *
 * Carries no body: the gateway's logout route reads nothing from the request but the CSRF
 * header `sendJson` already attaches.
 *
 * @returns the URL that also ends Keycloak's session. The caller navigates there with
 *   `browserLocation.assign`, never a `fetch`, since the browser must land on Keycloak's own
 *   page, not read its response as JSON.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function signOut(): Promise<LogoutOutcome> {
    const wire = await sendJson<LogoutResponse>(LOGOUT_URL, 'POST', undefined);
    return { logoutUrl: wire.logoutUrl };
}
