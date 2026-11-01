import { BFF_API } from '@/shared/api/client';

import { StandalonePage } from '../StandalonePage';

/** The gateway's OIDC login entry (ADR-030): a browser navigation here starts a real sign-in. */
const LOGIN_ENTRY_URL = `${BFF_API}/oauth2/authorization/keycloak`;

/**
 * The query parameter, and its value, the gateway redirects back with after a sign-in that
 * Keycloak or the gateway's callback refused (`?signin=failed`, ADR-030).
 */
const SIGNIN_FAILED_PARAM = 'signin';
const SIGNIN_FAILED_VALUE = 'failed';

/**
 * The screen `SessionGate` shows while nobody is signed in (ADR-030).
 *
 * The link is a plain `<a href>`, never a `fetch` or a client-side `navigate`: the gateway
 * answers this URL with a redirect to Keycloak's own login page, and only a top-level browser
 * navigation follows a cross-origin redirect anywhere the person can see and use it. `SignIn`
 * renders above `RouterProvider` (see `App.tsx`), so there is no router here to navigate with
 * even if that were safe.
 *
 * Reads `window.location.search` directly, for the same reason: it renders before any route
 * exists to carry the query string through `useSearchParams`.
 */
export function SignIn() {
    const failed =
        new URLSearchParams(window.location.search).get(SIGNIN_FAILED_PARAM) ===
        SIGNIN_FAILED_VALUE;

    return (
        <StandalonePage title="Sign in">
            {failed ? <p role="alert">Sign-in failed. Try again.</p> : null}
            <a href={LOGIN_ENTRY_URL}>Sign in with Keycloak</a>
        </StandalonePage>
    );
}
