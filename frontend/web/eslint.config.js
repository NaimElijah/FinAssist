import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

/**
 * The three ways code can name a member: `element.sink`, `element['sink']` and
 * ``element[`sink`]``, each as a selector suffix for the member expression.
 */
function memberNamed(sink) {
    return [
        `[property.name='${sink}']`,
        `[property.value='${sink}']`,
        `[property.quasis.0.value.cooked='${sink}']`,
    ];
}

/**
 * The three ways an object literal can name a key: `{ sink: … }`, `{ 'sink': … }` and
 * ``{ [`sink`]: … }``, each as a selector suffix for the property.
 */
function keyNamed(sink) {
    return [
        `[key.name='${sink}']`,
        `[key.value='${sink}']`,
        `[key.quasis.0.value.cooked='${sink}']`,
    ];
}

/** `Object.assign`, which copies an object literal's members onto its target as assignments. */
const OBJECT_ASSIGN = "CallExpression[callee.object.name='Object'][callee.property.name='assign']";

/** The message for one raw-HTML sink, naming it. */
function rawHtmlMessage(sink) {
    return (
        `Do not hand markup to the HTML parser through ${sink}: it bypasses React's escaping. ` +
        'Render text through JSX instead.'
    );
}

/** Members whose assignment parses the string assigned as HTML. */
const ASSIGNED_SINKS = ['innerHTML', 'outerHTML'];

/** Methods that parse a string argument as HTML, whatever object they are called on. */
const CALLED_SINKS = [
    'insertAdjacentHTML',
    'createContextualFragment',
    'setHTMLUnsafe',
    'parseFromString',
];

/** `document`'s own methods that parse a string argument into the page as HTML. */
const DOCUMENT_SINKS = ['write', 'writeln'];

/** The `document` object, named directly or as `window.document`. */
const DOCUMENT_OBJECT = ":matches([object.name='document'], [object.property.name='document'])";

/**
 * Each sink that hands a string to the HTML parser rather than rendering it as text, refused
 * everywhere: React escapes what it renders, and these are the ways around that. A read of
 * `innerHTML` stays allowed; only a write parses markup.
 */
const RAW_HTML_SINKS = [
    {
        selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
        message: rawHtmlMessage('dangerouslySetInnerHTML'),
    },
    // The same prop passed to createElement, or spread from an object.
    ...keyNamed('dangerouslySetInnerHTML').map((named) => ({
        selector: `Property${named}`,
        message: rawHtmlMessage('dangerouslySetInnerHTML'),
    })),
    {
        selector: "JSXAttribute[name.name='srcDoc']",
        message: rawHtmlMessage('srcDoc'),
    },
    ...ASSIGNED_SINKS.flatMap((sink) =>
        memberNamed(sink).map((named) => ({
            selector: `AssignmentExpression > MemberExpression.left${named}`,
            message: rawHtmlMessage(sink),
        })),
    ),
    ...ASSIGNED_SINKS.flatMap((sink) =>
        keyNamed(sink).map((named) => ({
            selector: `${OBJECT_ASSIGN} > ObjectExpression > Property${named}`,
            message: rawHtmlMessage(sink),
        })),
    ),
    ...CALLED_SINKS.flatMap((sink) =>
        memberNamed(sink).map((named) => ({
            selector: `CallExpression > MemberExpression.callee${named}`,
            message: rawHtmlMessage(sink),
        })),
    ),
    ...DOCUMENT_SINKS.flatMap((sink) =>
        memberNamed(sink).map((named) => ({
            selector: `CallExpression > MemberExpression.callee${named}${DOCUMENT_OBJECT}`,
            message: rawHtmlMessage(`document.${sink}`),
        })),
    ),
];

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
            'no-restricted-syntax': ['error', ...RAW_HTML_SINKS],
        },
    },
    {
        // A committed `.skip` or `.todo` drops a specification from the suite while it still
        // reports green, and a committed `.only` drops every other test in its file. Core
        // `no-restricted-syntax` covers the direct form (`it.skip`, `describe.only` or its alias
        // `suite.only`, which also reaches `it.skip.each`) and one chained modifier (`it.concurrent.skip`). `it.fails`
        // stays allowed: it asserts that a test fails, and fails itself once it passes.
        // A later setting of a rule replaces an earlier one rather than adding to it, so the
        // raw-HTML sinks are listed here again.
        files: ['**/*.test.{ts,tsx}'],
        rules: {
            'no-restricted-syntax': [
                'error',
                ...RAW_HTML_SINKS,
                {
                    selector:
                        'MemberExpression[object.name=/^(it|test|describe|suite)$/][property.name=/^(skip|todo|only)$/]',
                    message: SKIPPED_TEST_MESSAGE,
                },
                {
                    selector:
                        'MemberExpression[object.object.name=/^(it|test|describe|suite)$/][property.name=/^(skip|todo|only)$/]',
                    message: SKIPPED_TEST_MESSAGE,
                },
            ],
        },
    },
);
