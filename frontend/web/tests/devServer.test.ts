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
 * Narrows one proxy map entry to `ProxyOptions`, ruling out the string-shorthand form that
 * `server.proxy`'s type also allows but this config never uses.
 */
function proxyOptionsFor(
    map: Record<string, string | ProxyOptions> | undefined,
    prefix: string,
): ProxyOptions {
    const entry = map?.[prefix];
    if (typeof entry !== 'object') {
        throw new Error(`expected a ProxyOptions object for '${prefix}', got ${typeof entry}`);
    }
    return entry;
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
});
