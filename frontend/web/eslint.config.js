import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

const SKIPPED_TEST_MESSAGE =
    'Do not commit .skip, .todo or .only: each silently removes tests from the suite. Fix or ' +
    'delete the test; mark an expected failure with it.fails.';

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
    {
        // A committed `.skip` or `.todo` drops a specification from the suite while it still
        // reports green, and a committed `.only` drops every other test in its file. Core
        // `no-restricted-syntax` covers the direct form (`it.skip`, `describe.only`, which also
        // reaches `it.skip.each`) and one chained modifier (`it.concurrent.skip`). `it.fails`
        // stays allowed: it asserts that a test fails, and fails itself once it passes.
        files: ['**/*.test.{ts,tsx}'],
        rules: {
            'no-restricted-syntax': [
                'error',
                {
                    selector:
                        'MemberExpression[object.name=/^(it|test|describe)$/][property.name=/^(skip|todo|only)$/]',
                    message: SKIPPED_TEST_MESSAGE,
                },
                {
                    selector:
                        'MemberExpression[object.object.name=/^(it|test|describe)$/][property.name=/^(skip|todo|only)$/]',
                    message: SKIPPED_TEST_MESSAGE,
                },
            ],
        },
    },
);
