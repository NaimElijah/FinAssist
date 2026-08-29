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
        environment: 'jsdom',
        setupFiles: ['./tests/setup.ts'],
        include: [
            'tests/**/*.test.ts',
            'tests/**/*.test.tsx',
            'src/**/*.test.ts',
            'src/**/*.test.tsx',
        ],
    },
});
