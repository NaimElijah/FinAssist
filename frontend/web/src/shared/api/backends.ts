/**
 * The gateway the web client reaches, and the loopback proxy path to each of its three
 * surfaces: the ledger and the assistant it relays to, and its own `/bff` endpoints.
 *
 * `vite.config.ts` proxies `/ledger`, `/assistant` and `/bff` to the gateway and keeps each
 * path intact - the gateway's own routes carry `StripPrefix=1` and match on the full path, so
 * stripping it here too would leave nothing for them to match; `src/shared/api/client.ts` builds
 * its base paths from the same prefixes. This is the one place any of the three is written - a
 * proxy entry and an API base path can never name a different prefix by accident. See ADR-026
 * (one origin via proxy) and ADR-030 (BFF gateway, loopback bind, Host allow-list).
 */

/** Every dev/preview process binds here, never a wildcard interface (ADR-030). */
export const LOOPBACK_HOST = '127.0.0.1';

/**
 * The gateway's own address: a server-to-server call the dev server makes, not a URL a person
 * opens in a browser, so loopback is the right literal here (ADR-030). All three proxy entries
 * below share this one target - the gateway is the only thing the web client talks to directly.
 */
export const GATEWAY_TARGET = `http://${LOOPBACK_HOST}:8090`;

/** A prefix reached through the proxy: its path in the browser, and the origin it is sent to. */
export interface ProxiedBackend {
    readonly prefix: string;
    readonly target: string;
}

/** The ledger: accounting, budgeting and planning, proxied at `/ledger` through the gateway. */
export const LEDGER_BACKEND: ProxiedBackend = {
    prefix: '/ledger',
    target: GATEWAY_TARGET,
};

/** The assistant: portfolio, proxied at `/assistant` through the gateway. */
export const ASSISTANT_BACKEND: ProxiedBackend = {
    prefix: '/assistant',
    target: GATEWAY_TARGET,
};

/**
 * The gateway's own prefix: sign-in, callback and session endpoints. Named on its own rather
 * than only inside `BFF_BACKEND` because `client.ts` builds `BFF_API` straight from it - nothing
 * under `/bff` carries a versioned `/api/v1` segment for a base path to add.
 */
export const BFF_PREFIX = '/bff';

/** The gateway's own endpoints, proxied at `BFF_PREFIX` exactly like the two backends above. */
export const BFF_BACKEND: ProxiedBackend = {
    prefix: BFF_PREFIX,
    target: GATEWAY_TARGET,
};
