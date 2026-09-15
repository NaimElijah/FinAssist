// @vitest-environment node
// Reads the exported Vite config directly, so a regression in the proxy map or the loopback
// bind is a failing test here rather than something noticed only at `npm run dev` time.
/**
 * Proves the dev/preview server is bound to loopback and proxies both backends through one
 * origin, per ADR-026 (one origin via proxy) and ADR-027 (loopback bind, Host allow-list).
 */
import type { ProxyOptions } from 'vite';
import { describe, expect, it } from 'vitest';

import { ASSISTANT_BACKEND, LEDGER_BACKEND } from '../src/shared/api/backends';
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
        // literals (ADR-027's DNS-rebinding control); `true` would remove that protection.
        expect(viteConfig.server?.allowedHosts).not.toBe(true);
        expect(viteConfig.preview?.allowedHosts).not.toBe(true);
    });

    it('Given_the_vite_config_When_read_Then_server_and_preview_share_one_proxy_map', () => {
        expect(viteConfig.preview?.proxy).toBe(viteConfig.server?.proxy);
    });

    it('Given_the_ledger_proxy_entry_When_read_Then_it_targets_8080_with_changeOrigin', () => {
        const ledger = proxyOptionsFor(viteConfig.server?.proxy, LEDGER_BACKEND.prefix);

        expect(ledger.target).toBe('http://127.0.0.1:8080');
        expect(ledger.changeOrigin).toBe(true);
    });

    it('Given_the_assistant_proxy_entry_When_read_Then_it_targets_8000_with_changeOrigin', () => {
        const assistant = proxyOptionsFor(viteConfig.server?.proxy, ASSISTANT_BACKEND.prefix);

        expect(assistant.target).toBe('http://127.0.0.1:8000');
        expect(assistant.changeOrigin).toBe(true);
    });

    it('Given_a_ledger_prefixed_path_When_rewritten_Then_the_prefix_is_stripped', () => {
        const ledger = proxyOptionsFor(viteConfig.server?.proxy, LEDGER_BACKEND.prefix);

        expect(ledger.rewrite?.('/ledger/api/v1/accounts')).toBe('/api/v1/accounts');
    });

    it('Given_an_assistant_prefixed_path_When_rewritten_Then_the_prefix_is_stripped', () => {
        const assistant = proxyOptionsFor(viteConfig.server?.proxy, ASSISTANT_BACKEND.prefix);

        expect(assistant.rewrite?.('/assistant/api/v1/portfolio/holdings/VOO/position')).toBe(
            '/api/v1/portfolio/holdings/VOO/position',
        );
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
});
