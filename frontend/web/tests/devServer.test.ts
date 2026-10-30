// @vitest-environment node
// Reads the exported Vite config directly, so a regression in the proxy map or the loopback
// bind is a failing test here rather than something noticed only at `npm run dev` time.
/**
 * Proves the dev/preview server is bound to loopback and proxies every backend call - ledger,
 * assistant and the gateway's own `/bff` endpoints - through one origin to the gateway, per
 * ADR-026 (one origin via proxy) and ADR-030 (BFF gateway, loopback bind, Host allow-list).
 */
import { existsSync } from 'node:fs';
import http, { type IncomingHttpHeaders, type OutgoingHttpHeaders } from 'node:http';
import path from 'node:path';
import { fileURLToPath, URL } from 'node:url';

import { createServer, type ProxyOptions, type ViteDevServer } from 'vite';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import {
    ASSISTANT_BACKEND,
    BFF_BACKEND,
    GATEWAY_TARGET,
    LEDGER_BACKEND,
    LOOPBACK_HOST,
} from '../src/shared/api/backends';
import viteConfig from '../vite.config';

/**
 * Find the proxy map entry for a backend, keyed by its own anchored RegExp string rather than
 * the plain prefix - Vite compiles a key starting with `^` as a RegExp instead of matching it
 * with `startsWith`, so the map is no longer indexable by the plain prefix itself.
 */
function findProxyEntry(
    map: Record<string, string | ProxyOptions> | undefined,
    prefix: string,
): readonly [string, ProxyOptions] {
    const found = Object.entries(map ?? {}).find(([key]) => key.includes(prefix));
    const key = found?.[0];
    const options = found?.[1];
    if (key === undefined || typeof options !== 'object') {
        throw new Error(`no ProxyOptions entry found for prefix '${prefix}'`);
    }
    return [key, options];
}

/** The `ProxyOptions` half of a backend's proxy entry, ruling out the string-shorthand form. */
function proxyOptionsFor(
    map: Record<string, string | ProxyOptions> | undefined,
    prefix: string,
): ProxyOptions {
    return findProxyEntry(map, prefix)[1];
}

/** The map key half of a backend's proxy entry, compiled as the RegExp Vite matches paths with. */
function regexKeyFor(
    map: Record<string, string | ProxyOptions> | undefined,
    prefix: string,
): RegExp {
    return new RegExp(findProxyEntry(map, prefix)[0]);
}

describe('the dev/preview server', () => {
    it('Given_the_vite_config_When_read_Then_server_and_preview_both_bind_loopback', () => {
        expect(viteConfig.server?.host).toBe('127.0.0.1');
        expect(viteConfig.preview?.host).toBe('127.0.0.1');
    });

    it('Given_the_vite_config_When_read_Then_allowedHosts_is_never_set_to_true', () => {
        // Vite 8's default already refuses any Host but localhost, its subdomains and IP
        // literals (ADR-030's DNS-rebinding control); `true` would remove that protection.
        expect(viteConfig.server?.allowedHosts).not.toBe(true);
        expect(viteConfig.preview?.allowedHosts).not.toBe(true);
    });

    it('Given_the_vite_config_When_read_Then_server_and_preview_both_refuse_framing', () => {
        // A policy holding only frame-ancestors restricts no script, so Fast Refresh's inline
        // preamble keeps working in development; #165 tracks the full policy.
        expect(viteConfig.server?.headers?.['Content-Security-Policy']).toBe(FRAME_ANCESTORS_NONE);
        expect(viteConfig.preview?.headers?.['Content-Security-Policy']).toBe(FRAME_ANCESTORS_NONE);
    });

    it('Given_the_vite_config_When_read_Then_server_and_preview_share_one_proxy_map', () => {
        expect(viteConfig.preview?.proxy).toBe(viteConfig.server?.proxy);
    });

    it('Given_the_vite_config_When_read_Then_exactly_the_three_backend_prefixes_are_proxied', () => {
        // Every backend call goes through the gateway now (ADR-030): a fourth entry here would
        // be a path the gateway was never told to expect, and a missing one would leave a whole
        // prefix unproxied.
        expect(Object.keys(viteConfig.server?.proxy ?? {})).toHaveLength(3);
    });

    it('Given_the_ledger_proxy_entry_When_read_Then_it_targets_the_gateway_and_keeps_its_path', () => {
        const ledger = proxyOptionsFor(viteConfig.server?.proxy, LEDGER_BACKEND.prefix);

        expect(ledger.target).toBe(GATEWAY_TARGET);
        expect(ledger.changeOrigin).toBe(true);
        // No `rewrite`: Vite forwards the path unchanged only when this is entirely absent, not
        // merely an identity function. The gateway's own route matches the full
        // `/ledger/api/v1/**` path and strips the prefix itself (`StripPrefix=1`); stripping it
        // here too would leave the gateway nothing to match, and its deny-by-default security
        // chain would refuse every call.
        expect(ledger.rewrite).toBeUndefined();
    });

    it('Given_the_assistant_proxy_entry_When_read_Then_it_targets_the_gateway_and_keeps_its_path', () => {
        const assistant = proxyOptionsFor(viteConfig.server?.proxy, ASSISTANT_BACKEND.prefix);

        expect(assistant.target).toBe(GATEWAY_TARGET);
        expect(assistant.changeOrigin).toBe(true);
        expect(assistant.rewrite).toBeUndefined();
    });

    it('Given_the_bff_proxy_entry_When_read_Then_it_targets_the_gateway_and_keeps_its_path', () => {
        const bff = proxyOptionsFor(viteConfig.server?.proxy, BFF_BACKEND.prefix);

        expect(bff.target).toBe(GATEWAY_TARGET);
        expect(bff.changeOrigin).toBe(true);
        expect(bff.rewrite).toBeUndefined();
    });

    it('Given_the_ledger_proxy_key_When_tested_as_a_RegExp_Then_it_matches_the_prefix_and_its_subpaths', () => {
        const ledgerKey = regexKeyFor(viteConfig.server?.proxy, LEDGER_BACKEND.prefix);

        expect(ledgerKey.test('/ledger')).toBe(true);
        expect(ledgerKey.test('/ledger/')).toBe(true);
        expect(ledgerKey.test('/ledger/api/v1/accounts')).toBe(true);
    });

    it('Given_the_ledger_proxy_key_When_tested_as_a_RegExp_Then_it_does_not_match_a_similarly_named_path', () => {
        // The vulnerability this key exists to close: a plain-string key `/ledger` is matched
        // with `url.startsWith('/ledger')`, so `/ledgerx...` and `/ledgers/...` would also be
        // proxied to the ledger even though neither names its prefix.
        const ledgerKey = regexKeyFor(viteConfig.server?.proxy, LEDGER_BACKEND.prefix);

        expect(ledgerKey.test('/ledgerx')).toBe(false);
        expect(ledgerKey.test('/ledgers/api')).toBe(false);
    });

    it('Given_the_assistant_proxy_key_When_tested_as_a_RegExp_Then_it_matches_the_prefix_and_its_subpaths', () => {
        const assistantKey = regexKeyFor(viteConfig.server?.proxy, ASSISTANT_BACKEND.prefix);

        expect(assistantKey.test('/assistant')).toBe(true);
        expect(assistantKey.test('/assistant/')).toBe(true);
        expect(assistantKey.test('/assistant/api/v1/portfolio/holdings/VOO/position')).toBe(true);
    });

    it('Given_the_assistant_proxy_key_When_tested_as_a_RegExp_Then_it_does_not_match_a_similarly_named_path', () => {
        const assistantKey = regexKeyFor(viteConfig.server?.proxy, ASSISTANT_BACKEND.prefix);

        expect(assistantKey.test('/assistantx')).toBe(false);
        expect(assistantKey.test('/assistants/api')).toBe(false);
    });

    it('Given_the_bff_proxy_key_When_tested_as_a_RegExp_Then_it_matches_the_prefix_and_its_subpaths', () => {
        const bffKey = regexKeyFor(viteConfig.server?.proxy, BFF_BACKEND.prefix);

        expect(bffKey.test('/bff')).toBe(true);
        expect(bffKey.test('/bff/')).toBe(true);
        expect(bffKey.test('/bff/oauth2/authorization/keycloak')).toBe(true);
    });

    it('Given_the_bff_proxy_key_When_tested_as_a_RegExp_Then_it_does_not_match_a_lookalike_path', () => {
        // The same closed vulnerability as the ledger and assistant keys above: `/bffx` and
        // `/bff-evil` both start with characters that resemble the prefix without naming it, and
        // a plain-string key would proxy both of them to the gateway's own endpoints.
        const bffKey = regexKeyFor(viteConfig.server?.proxy, BFF_BACKEND.prefix);

        expect(bffKey.test('/bffx')).toBe(false);
        expect(bffKey.test('/bff-evil')).toBe(false);
    });
});

/** The policy both servers send, so no other page can frame the app. */
const FRAME_ANCESTORS_NONE = "frame-ancestors 'none'";

/** The endpoint Vite mounts to open a file in the owner's editor, from its error overlay. */
const OPEN_IN_EDITOR = '/__open-in-editor';

/** The directory holding this suite's Vite config, which a dev server started here serves. */
const WEB_ROOT = fileURLToPath(new URL('..', import.meta.url));

/**
 * Starting a dev server loads the config, its plugins and the app's index, which takes seconds,
 * more on a loaded CI runner, so it runs once under a budget of its own rather than Vitest's
 * default hook timeout.
 */
const SERVER_START_TIMEOUT_MS = 60_000;

/**
 * A path that names nothing on disk, so a request reaching Vite's own middleware can never
 * launch an editor: launch-editor returns before spawning anything for a path that does not
 * exist. Each test re-checks that before sending it.
 */
const ABSENT_FILE = path.join(WEB_ROOT, 'no-such-directory-for-dev-server-tests', 'absent.txt');

/** The open-in-editor request for `file`, as the overlay builds it. */
function openInEditorPathFor(file: string): string {
    return `${OPEN_IN_EDITOR}?file=${encodeURIComponent(file)}`;
}

/** One answer from the dev server: its status and its headers. */
interface Answer {
    readonly status: number;
    readonly headers: IncomingHttpHeaders;
}

/**
 * Send one GET to the dev server over `node:http`, which, unlike `fetch`, sends the `Host` and
 * `Sec-Fetch-*` headers exactly as given, the way a browser would set them.
 */
function get(port: number, requestPath: string, headers: OutgoingHttpHeaders): Promise<Answer> {
    return new Promise((resolve, reject) => {
        const request = http.get(
            { host: LOOPBACK_HOST, port, path: requestPath, headers },
            (response) => {
                response.resume();
                response.on('end', () => {
                    resolve({ status: response.statusCode ?? 0, headers: response.headers });
                });
            },
        );
        request.on('error', reject);
    });
}

/** The port a listening dev server was given, after asking for port 0. */
function portOf(server: ViteDevServer): number {
    const address = server.httpServer?.address();
    if (address === null || address === undefined || typeof address === 'string') {
        throw new Error('the dev server is not listening on a TCP port');
    }
    return address.port;
}

describe('the running dev server', () => {
    let server: ViteDevServer;
    let port: number;

    beforeAll(async () => {
        // The real config file, so every plugin and server option under test is the one
        // `npm run dev` starts with. Port 0 takes a free port; discovery is off because no
        // test here requests a module, and scanning the app's imports would only slow it down.
        server = await createServer({
            configFile: fileURLToPath(new URL('../vite.config.ts', import.meta.url)),
            root: WEB_ROOT,
            logLevel: 'silent',
            clearScreen: false,
            server: { port: 0, strictPort: true },
            optimizeDeps: { noDiscovery: true },
        });
        await server.listen();
        port = portOf(server);
    }, SERVER_START_TIMEOUT_MS);

    afterAll(async () => {
        await server.close();
    });

    it.each(['cross-site', 'same-site', 'none'])(
        'Given_a_request_whose_sec_fetch_site_is_%s_When_open_in_editor_is_called_Then_the_dev_server_refuses_it_403',
        async (fetchSite) => {
            expect(existsSync(ABSENT_FILE)).toBe(false);

            const answer = await get(port, openInEditorPathFor(ABSENT_FILE), {
                host: `localhost:${String(port)}`,
                'sec-fetch-site': fetchSite,
            });

            expect(answer.status).toBe(403);
        },
    );

    it('Given_a_request_without_sec_fetch_site_When_open_in_editor_is_called_Then_the_dev_server_refuses_it_403', async () => {
        // Every browser the overlay runs in sends the header, so its absence means a client that
        // is not that overlay.
        expect(existsSync(ABSENT_FILE)).toBe(false);

        const answer = await get(port, openInEditorPathFor(ABSENT_FILE), {
            host: `localhost:${String(port)}`,
        });

        expect(answer.status).toBe(403);
    });

    it('Given_the_dev_server_When_the_app_shell_is_served_Then_it_carries_frame_ancestors_none', async () => {
        const answer = await get(port, '/accounts', {
            host: `localhost:${String(port)}`,
            accept: 'text/html',
        });

        expect(answer.status).toBe(200);
        expect(answer.headers['content-type']).toContain('text/html');
        expect(answer.headers['content-security-policy']).toBe(FRAME_ANCESTORS_NONE);
    });

    it('Given_a_same_origin_request_without_a_file_When_open_in_editor_is_called_Then_vites_own_middleware_answers_500', async () => {
        // Vite's error overlay calls the endpoint from the page itself, so a same-origin request
        // must still reach launch-editor's middleware; without a `file` it answers 500 and opens
        // nothing, which proves the guard let it through without launching an editor.
        const answer = await get(port, OPEN_IN_EDITOR, {
            host: `localhost:${String(port)}`,
            'sec-fetch-site': 'same-origin',
        });

        expect(answer.status).toBe(500);
    });
});
