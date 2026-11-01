/**
 * The one place the app's script moves the whole browser window away from itself, rather than
 * routing within the SPA. Only sign-out uses it, to reach Keycloak's end-session endpoint; sign-in
 * is a plain link (`SignIn`), which needs no script.
 *
 * Leaving for Keycloak cannot be a client-side route, and a `fetch` would follow the gateway's
 * redirect invisibly and land nobody on Keycloak's page.
 *
 * A plain object with a mutable method, rather than a bare function, lets a test replace
 * `assign` with `vi.spyOn` - the same pattern this codebase already uses for
 * `queryClient.invalidateQueries` - without threading a navigation callback through every
 * component that might ever sign someone out.
 */
import { log } from '@/shared/lib/logger';

/** The only schemes a top-level navigation may target. */
const ALLOWED_PROTOCOLS = new Set(['http:', 'https:']);

/** Moves the browser window; replaceable, so a test can intercept it without leaving jsdom. */
export interface BrowserLocation {
    /**
     * Navigate the whole window to `url`.
     *
     * `url` arrives from the server - the gateway's `logoutUrl` - so a malformed or tampered
     * value must never reach `window.location`: a `javascript:` URL would execute in the page
     * rather than navigate away from it. A refused URL is logged and otherwise ignored, since
     * there is nowhere safer to send the browser.
     *
     * @param url - must parse as an absolute URL with an `http:` or `https:` scheme.
     */
    assign(url: string): void;
}

/** Parses *url*, or `undefined` when it does not parse as an absolute URL at all. */
function parseUrl(url: string): URL | undefined {
    try {
        return new URL(url);
    } catch {
        return undefined;
    }
}

function isAllowedUrl(url: string): boolean {
    const parsed = parseUrl(url);
    return parsed !== undefined && ALLOWED_PROTOCOLS.has(parsed.protocol);
}

/**
 * The app's one `BrowserLocation`. Nothing in `main.tsx` replaces it; a test does, with
 * `vi.spyOn(browserLocation, 'assign')`, so it can assert a navigation happened without the
 * browser actually leaving the test page.
 */
export const browserLocation: BrowserLocation = {
    assign(url) {
        if (!isAllowedUrl(url)) {
            // Logs the scheme alone, never `url` itself: the one caller is `useSignOut`, which
            // passes the gateway's `logoutUrl`, and that carries `id_token_hint` as a query
            // parameter. `url` is not among logger.ts's redacted key
            // fragments, and should not be - nearly every other call site in this client logs a
            // benign API path under that same name, so redacting the field at the boundary would
            // silence all of them to fix one call site.
            log.error('refused to navigate to a non-http(s) url', {
                scheme: parseUrl(url)?.protocol,
            });
            return;
        }
        window.location.assign(url);
    },
};
