/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
    plugins: [react()],
    resolve: {
        // `@/features/portfolio` reads better than a run of `../../..`, and it makes a
        // cross-feature import obvious in review and to the architecture test.
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
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
            exclude: ['src/**/*.test.{ts,tsx}', 'src/**/index.ts'],
        },
    },
});
