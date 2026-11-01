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

/** The variable naming the gateway's published port, as Compose and `.env.example` spell it. */
export const GATEWAY_PORT_VARIABLE = 'GATEWAY_PORT';

/** The port Compose publishes the gateway on when `GATEWAY_PORT` is unset or empty. */
export const DEFAULT_GATEWAY_PORT = 8090;

/** The highest TCP port; 0 is no port a service can be reached on. */
const HIGHEST_PORT = 65535;

/** Digits only, so a sign, a decimal point, an exponent or whitespace is refused, not coerced. */
const PORT_DIGITS = /^\d+$/;

/**
 * The gateway's own address, which every proxy entry shares - the gateway is the only thing the
 * web client talks to directly. A server-to-server call the dev server makes, not a URL a person
 * opens in a browser, so loopback is the right literal here (ADR-030).
 *
 * The port follows `GATEWAY_PORT` exactly as Compose publishes it, `${GATEWAY_PORT:-8090}`, so
 * moving the gateway moves the proxy with it: unset or empty means `DEFAULT_GATEWAY_PORT`.
 *
 * @param environment - the variables to read `GATEWAY_PORT` from; only that key is read.
 * @returns the gateway's origin, such as `"http://127.0.0.1:8090"`.
 * @throws Error - when `GATEWAY_PORT` is set to anything but a whole number from 1 to 65535, so a
 *   mistyped port fails when the config loads rather than as every proxied call failing.
 */
export function gatewayTarget(environment: Readonly<Record<string, string | undefined>>): string {
    const value = environment[GATEWAY_PORT_VARIABLE];
    if (value === undefined || value === '') {
        return `http://${LOOPBACK_HOST}:${String(DEFAULT_GATEWAY_PORT)}`;
    }
    const port = Number(value);
    if (!PORT_DIGITS.test(value) || port < 1 || port > HIGHEST_PORT) {
        // A port is not a secret, and naming the value is what lets the owner find the typo.
        throw new Error(
            `${GATEWAY_PORT_VARIABLE} must be a port number from 1 to ${String(HIGHEST_PORT)}, ` +
                `but is ${JSON.stringify(value)}`,
        );
    }
    return `http://${LOOPBACK_HOST}:${String(port)}`;
}

/**
 * A prefix reached through the proxy: its path in the browser. Every one is sent to the gateway,
 * at `gatewayTarget`.
 */
export interface ProxiedBackend {
    readonly prefix: string;
}

/** The ledger: accounting, budgeting and planning, proxied at `/ledger` through the gateway. */
export const LEDGER_BACKEND: ProxiedBackend = {
    prefix: '/ledger',
};

/** The assistant: portfolio, proxied at `/assistant` through the gateway. */
export const ASSISTANT_BACKEND: ProxiedBackend = {
    prefix: '/assistant',
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
};
