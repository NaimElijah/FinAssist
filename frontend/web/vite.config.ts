import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import type { ProxyOptions } from 'vite';
// defineConfig comes from vitest/config, not vite: it carries the `test` key's types, which
// the triple-slash reference used to supply before this file imported from vitest at all.
import { coverageConfigDefaults, defineConfig } from 'vitest/config';

// Vite's forward-compat warning for `configLoader: 'native'` flags this extensionless import.
// The active default loader is still `bundle`, which supports it, so nothing here is broken;
// `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` would silence the warning, and is deliberately not
// set, so a future move to the native loader stays a decision rather than a muted surprise.
import {
    ASSISTANT_BACKEND,
    LEDGER_BACKEND,
    LOOPBACK_HOST,
    type ProxiedBackend,
} from './src/shared/api/backends';

/**
 * The proxy map key for one backend, anchored so only its own prefix matches.
 *
 * A plain string key is matched with `url.startsWith(context)`
 * (`doesProxyContextMatchUrl`, `vite/dist/node/chunks/node.js`), so the literal prefix
 * `/ledger` would also match `/ledgerx...` and `/ledgers/...` - any path merely starting with
 * the same characters, not just the prefix itself. A key starting with `^` is instead compiled
 * as a RegExp and matched with `.test()`, so anchoring the prefix to the end of the path or a
 * following `/` accepts only the prefix and its own sub-paths.
 */
function proxyKey(backend: ProxiedBackend): string {
    return `^${backend.prefix}(/|$)`;
}

/**
 * Build one backend's proxy entry: forward its prefix to its real origin with the prefix
 * stripped, and `changeOrigin` so the backend's own Host allow-list sees its own host rather
 * than the dev server's (ADR-027) — each backend then needs only its own name on its list.
 */
function proxyEntry(backend: ProxiedBackend): ProxyOptions {
    return {
        target: backend.target,
        changeOrigin: true,
        rewrite: (path) => path.slice(backend.prefix.length),
    };
}

// Defined once and given to both `server.proxy` and `preview.proxy` below, so `vite preview`
// can never drift from `vite dev` (ADR-026: one origin, both environments).
const proxy: Record<string, ProxyOptions> = {
    [proxyKey(LEDGER_BACKEND)]: proxyEntry(LEDGER_BACKEND),
    [proxyKey(ASSISTANT_BACKEND)]: proxyEntry(ASSISTANT_BACKEND),
};

export default defineConfig({
    plugins: [react()],
    resolve: {
        // `@/features/portfolio` reads better than a run of `../../..`, and it makes a
        // cross-feature import obvious in review and to the architecture test.
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    server: {
        // Loopback only, never a wildcard interface (ADR-027).
        host: LOOPBACK_HOST,
        // `allowedHosts` is left at Vite 8's default rather than set here: `localhost`, its
        // subdomains and every IP literal are allowed already, and any other Host is refused —
        // that refusal is the DNS-rebinding protection ADR-027 relies on. Do not set this to
        // `true`; that would accept every Host and remove the protection entirely.
        proxy,
    },
    preview: {
        host: LOOPBACK_HOST,
        proxy,
    },
    test: {
        globals: true,
        // Node stays the default and jsdom is opted into per file with
        // `// @vitest-environment jsdom`. Flipping the default would put every test that reads
        // the filesystem one forgotten pragma away from failing: under jsdom `import.meta.url`
        // is an http: URL and fileURLToPath rejects it, which tests/architecture.test.ts
        // already records having hit. Vitest 3 removed environmentMatchGlobs, so the choice is
        // per-file pragmas or a projects split, and four DOM tests do not justify the latter.
        environment: 'node',
        include: [
            'tests/**/*.test.ts',
            'tests/**/*.test.tsx',
            'src/**/*.test.ts',
            'src/**/*.test.tsx',
        ],
        coverage: {
            provider: 'v8',
            reporter: ['text', 'html', 'lcov'],
            include: ['src/**/*.{ts,tsx}'],
            // No thresholds. Coverage is a diagnostic here, never a gate; see
            // docs/ADRs/ADR-017-test-strategy.md.
            //
            // Spread rather than replace: naming `exclude` overrides Vitest's own defaults
            // wholesale, which would silently pull .d.ts files and other non-source into the
            // report and make the diagnostic misleading.
            exclude: [
                ...coverageConfigDefaults.exclude,
                'src/**/*.test.{ts,tsx}',
                'src/**/index.ts',
                // Generated from the committed OpenAPI documents (ADR-029): types only, no
                // runtime code, so there is nothing here for coverage to measure.
                'src/shared/api/contracts/**',
            ],
        },
    },
});
