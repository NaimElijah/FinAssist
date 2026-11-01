import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { loadEnv, type Plugin, type ProxyOptions } from 'vite';
// defineConfig comes from vitest/config, not vite: it carries the `test` key's types, which
// the triple-slash reference used to supply before this file imported from vitest at all.
import { coverageConfigDefaults, defineConfig } from 'vitest/config';

// Vite's forward-compat warning for `configLoader: 'native'` flags this extensionless import.
// The active default loader is still `bundle`, which supports it, so nothing here is broken;
// `VITE_CONFIG_NATIVE_IGNORE_WARNING=true` would silence the warning, and is deliberately not
// set, so a future move to the native loader stays a decision rather than a muted surprise.
import {
    ASSISTANT_BACKEND,
    BFF_BACKEND,
    GATEWAY_PORT_VARIABLE,
    gatewayTarget,
    LEDGER_BACKEND,
    LOOPBACK_HOST,
    type ProxiedBackend,
} from './src/shared/api/backends';

/** Where Compose reads `.env`, two levels above this workspace. */
const REPOSITORY_ROOT = fileURLToPath(new URL('../..', import.meta.url));

/**
 * The mode `loadEnv` is asked for. It only adds `.env.<mode>` files beside `.env`, which the
 * repository does not keep: Compose reads `.env` alone, and so, in effect, does this.
 */
const ENV_FILE_MODE = 'development';

/**
 * The gateway every prefix is proxied to, at the port Compose publishes it on. `GATEWAY_PORT` is
 * read from the process environment first, then from the repository's `.env`; `loadEnv` is given
 * that one name as its prefix, so no other variable in `.env`, its secrets among them, is
 * returned to this config. Exported for the proxy's own tests.
 *
 * @throws Error - when the config loads with a `GATEWAY_PORT` that is not a port number.
 */
export const GATEWAY_TARGET = gatewayTarget(
    loadEnv(ENV_FILE_MODE, REPOSITORY_ROOT, GATEWAY_PORT_VARIABLE),
);

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
 * Build one prefix's proxy entry: forward it to the gateway, with `changeOrigin` so the
 * gateway's own Host allow-list sees its own address rather than the dev server's (ADR-030), and
 * deliberately no `rewrite`.
 *
 * All three prefixes now go to the gateway rather than straight to a service. Its own routes
 * carry `StripPrefix=1` and match on the full `/ledger/api/v1/**` or `/assistant/api/v1/**`
 * path, and `/bff/**` is matched whole too, so stripping the prefix here as well would leave the
 * gateway nothing to match — its deny-by-default security chain would then refuse every call.
 */
function proxyEntry(): ProxyOptions {
    return {
        target: GATEWAY_TARGET,
        changeOrigin: true,
    };
}

// Defined once and given to both `server.proxy` and `preview.proxy` below, so `vite preview`
// can never drift from `vite dev` (ADR-026: one origin, both environments).
const proxy: Record<string, ProxyOptions> = {
    [proxyKey(LEDGER_BACKEND)]: proxyEntry(),
    [proxyKey(ASSISTANT_BACKEND)]: proxyEntry(),
    [proxyKey(BFF_BACKEND)]: proxyEntry(),
};

/**
 * Sent on every response the dev server and `vite preview` answer themselves, the app shell
 * included, so no other page can frame the app and steer the owner's clicks inside it.
 *
 * Only `frame-ancestors`: a policy restricting scripts would break Fast Refresh's inline preamble
 * in development, and the full policy, for a built bundle, is #165's. A proxied response carries
 * whatever the gateway sends instead.
 */
const FRAMING_REFUSED_HEADERS: Readonly<Record<string, string>> = {
    'Content-Security-Policy': "frame-ancestors 'none'",
};

/** The endpoint Vite's error overlay calls to open a file in the owner's editor. */
const OPEN_IN_EDITOR_PATH = '/__open-in-editor';

/** The only `Sec-Fetch-Site` value a request from the app's own page carries. */
const SAME_ORIGIN = 'same-origin';

const FORBIDDEN = 403;

/**
 * Refuse `/__open-in-editor` with 403 unless the request comes from the app's own page.
 *
 * Vite mounts that endpoint after its Host check but checks no origin, and it launches a process
 * on the owner's machine for whatever file a GET names. The error overlay calls it from the page
 * itself, so its click-to-open keeps working; any other page, including one on another
 * `localhost` port, which is same-site but not same-origin, is refused. A `configureServer`
 * middleware runs before Vite mounts its own, so this one sees the request first. Preview
 * mounts no such endpoint, so this plugin has no preview hook.
 */
function openInEditorSameOriginOnly(): Plugin {
    return {
        name: 'finassist:open-in-editor-same-origin-only',
        configureServer(server) {
            server.middlewares.use(OPEN_IN_EDITOR_PATH, (request, response, next) => {
                if (request.headers['sec-fetch-site'] === SAME_ORIGIN) {
                    next();
                    return;
                }
                response.statusCode = FORBIDDEN;
                response.end();
            });
        },
    };
}

export default defineConfig({
    plugins: [react(), openInEditorSameOriginOnly()],
    resolve: {
        // `@/features/portfolio` reads better than a run of `../../..`, and it makes a
        // cross-feature import obvious in review and to the architecture test.
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    server: {
        // Loopback only, never a wildcard interface (ADR-030).
        host: LOOPBACK_HOST,
        // `allowedHosts` is left at Vite 8's default rather than set here: `localhost`, its
        // subdomains and every IP literal are allowed already, and any other Host is refused —
        // that refusal is the DNS-rebinding protection ADR-030 relies on. Do not set this to
        // `true`; that would accept every Host and remove the protection entirely.
        // `fs.strict` and `fs.allow` are left at their defaults too: strict, and this workspace
        // alone, which keeps the repository root's `.env` out of what the dev server serves.
        headers: FRAMING_REFUSED_HEADERS,
        // No CORS. The app calls only its own origin, and Vite's default would answer every
        // `localhost` origin on any port, letting another local page read whatever this server
        // serves; the CSRF defence counts on no other origin being answered.
        cors: false,
        proxy,
    },
    preview: {
        host: LOOPBACK_HOST,
        headers: FRAMING_REFUSED_HEADERS,
        // Off for the same reason as the dev server's.
        cors: false,
        proxy,
    },
    test: {
        globals: true,
        // Vitest's own default (5000ms) is tight for a form test: several drive real keystrokes
        // through userEvent across a handful of fields, and a CI runner is measurably slower
        // than a developer's machine under load. 15s leaves headroom without hiding a test that
        // is genuinely hung.
        testTimeout: 15000,
        // Node stays the default and jsdom is opted into per file with
        // `// @vitest-environment jsdom`. Flipping the default would put every test that reads
        // the filesystem one forgotten pragma away from failing: under jsdom `import.meta.url`
        // is an http: URL and fileURLToPath rejects it, which tests/architecture.test.ts
        // already records having hit. Vitest 3 removed environmentMatchGlobs, so the choice is
        // per-file pragmas or a projects split; a pragma states each test file's environment in
        // that file, where a split would move it into this config, away from the test it governs.
        environment: 'node',
        // Supplies, in jsdom files only, the browser APIs jsdom lacks, such as ResizeObserver,
        // so a screen test rendering a component that uses one needs no stub of its own.
        setupFiles: ['./src/shared/testing/browserGlobals.ts'],
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
                // Test helpers: code that only tests run, so measuring it would report on the
                // tests rather than on the source they exercise.
                'src/shared/testing/**',
                // Generated from the committed OpenAPI documents (ADR-029): types only, no
                // runtime code, so there is nothing here for coverage to measure.
                'src/shared/api/contracts/**',
            ],
        },
    },
});
