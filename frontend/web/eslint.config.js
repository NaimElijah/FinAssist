import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
    {
        // Generated from the committed OpenAPI documents (ADR-029); hand-editing would be
        // overwritten by the next `npm run generate:api`, so lint has nothing useful to say
        // about it.
        ignores: ['dist', 'coverage', 'node_modules', 'src/shared/api/contracts/**'],
    },
    js.configs.recommended,
    {
        files: ['**/*.{ts,tsx}'],
        // Type-aware rules are scoped to TypeScript files: applying them repository-wide makes
        // ESLint demand a TS program for its own config file, which has none.
        //
        // TypeScript is pinned to 6.0.3 rather than 7.x because typescript-eslint 8 declares
        // `typescript: >=4.8.4 <6.1.0`. Revisit when it supports 7.
        extends: [tseslint.configs.strictTypeChecked, tseslint.configs.stylisticTypeChecked],
        languageOptions: {
            parserOptions: {
                projectService: true,
                tsconfigRootDir: import.meta.dirname,
            },
        },
        plugins: {
            'react-hooks': reactHooks,
            'react-refresh': reactRefresh,
        },
        rules: {
            ...reactHooks.configs.recommended.rules,
            'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
            // Structured records go through src/shared/lib/logger.ts, which is the only file
            // that disables this. Mirrors ruff's T20 ban on `print` in the backend (ADR-019).
            'no-console': 'error',
        },
    },
);
