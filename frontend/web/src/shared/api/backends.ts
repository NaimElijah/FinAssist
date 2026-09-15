/**
 * The two backend services the web client reaches, and the loopback proxy path to each.
 *
 * `vite.config.ts` proxies `/ledger` and `/assistant` to the two services and strips the prefix;
 * `src/shared/api/client.ts` builds its base paths from the same prefix. Both need the same
 * prefix and target, so this is the one place either constant is written — a proxy entry and an
 * API base path can never name different services by accident. See ADR-026 (one origin via
 * proxy) and ADR-027 (loopback bind, Host allow-list).
 */

/** Every dev/preview process binds here, never a wildcard interface (ADR-027). */
export const LOOPBACK_HOST = '127.0.0.1';

/** A backend reached through the proxy: its path prefix in the browser, and its real origin. */
export interface ProxiedBackend {
    readonly prefix: string;
    readonly target: string;
}

/** The ledger: accounting, budgeting and planning, proxied at `/ledger`. */
export const LEDGER_BACKEND: ProxiedBackend = {
    prefix: '/ledger',
    target: `http://${LOOPBACK_HOST}:8080`,
};

/** The assistant: portfolio, proxied at `/assistant`. */
export const ASSISTANT_BACKEND: ProxiedBackend = {
    prefix: '/assistant',
    target: `http://${LOOPBACK_HOST}:8000`,
};
