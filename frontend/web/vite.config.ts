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
        // Node, not jsdom: the only test reads the source tree. A DOM environment and a
        // testing-library stack arrive with the first component test that needs them.
        environment: 'node',
        include: [
            'tests/**/*.test.ts',
            'tests/**/*.test.tsx',
            'src/**/*.test.ts',
            'src/**/*.test.tsx',
        ],
    },
});
