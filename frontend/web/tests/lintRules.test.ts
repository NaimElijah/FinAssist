// @vitest-environment node
// Lints snippets through ESLint's own API with the committed config, so a rule that fails the
// build is proven to fail it, rather than trusted to.
/**
 * Proves `eslint.config.js` refuses every raw-HTML sink it names: the `dangerouslySetInnerHTML`
 * and `srcDoc` props, an `innerHTML` or `outerHTML` assignment, `insertAdjacentHTML`,
 * `createContextualFragment`, `setHTMLUnsafe`, `parseFromString`, and `document.write` and
 * `document.writeln`, each member named directly or by a string or template-literal key, and an
 * `innerHTML` or `outerHTML` member given to `Object.assign`. React renders text safely; each of
 * these hands a string to the HTML parser instead.
 */
import { fileURLToPath, URL } from 'node:url';

import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it } from 'vitest';

/** The web client's root, where `eslint.config.js` sits. */
const WEB_ROOT = fileURLToPath(new URL('..', import.meta.url));

/**
 * Paths the snippets are linted as, so each picks up the config block its file glob selects. The
 * snippet stands in for the file's text in memory only.
 */
const COMPONENT_FILE = 'src/app/NotFound.tsx';
const MODULE_FILE = 'src/shared/lib/locale.ts';
const TEST_FILE = 'tests/lintRules.test.ts';

const RESTRICTED_SYNTAX = 'no-restricted-syntax';

/**
 * Loading the config, its plugins and the parser takes seconds, more on a loaded CI runner, so it
 * happens once, before any test, under a budget of its own rather than inside the first test's.
 */
const CONFIG_LOAD_TIMEOUT_MS = 60_000;

/** A component rendering `element`, the JSX under test. */
function componentRendering(element: string): string {
    return [
        'export function Raw({ html }: { html: string }) {',
        `    return ${element};`,
        '}',
        '',
    ].join('\n');
}

/** A module whose one function runs `statement`, with `element` and `html` in scope. */
function moduleRunning(statement: string): string {
    return [
        'export function write(element: Element, html: string): void {',
        `    ${statement}`,
        '}',
        '',
    ].join('\n');
}

describe('the lint rules against raw-HTML sinks', () => {
    let eslint: ESLint;

    beforeAll(async () => {
        // Only the rule under test runs, so the parser needs no TypeScript program: building one
        // for the whole project takes longer than a test should, and no selector here reads
        // a type. The selectors and the file globs are still the committed config's own.
        eslint = new ESLint({
            cwd: WEB_ROOT,
            ruleFilter: ({ ruleId }) => ruleId === RESTRICTED_SYNTAX,
            overrideConfig: { languageOptions: { parserOptions: { projectService: false } } },
        });
        await eslint.lintText('', { filePath: MODULE_FILE });
    }, CONFIG_LOAD_TIMEOUT_MS);

    /** The `no-restricted-syntax` messages ESLint reports for `code`, linted as `filePath`. */
    async function restrictedSyntaxIn(code: string, filePath: string): Promise<string[]> {
        const [result] = await eslint.lintText(code, { filePath });
        return (result?.messages ?? [])
            .filter((message) => message.ruleId === RESTRICTED_SYNTAX)
            .map((message) => message.message);
    }

    it.each([
        [
            'dangerouslySetInnerHTML',
            'dangerouslySetInnerHTML',
            '<div dangerouslySetInnerHTML={{ __html: html }} />',
        ],
        ['srcDoc', 'srcDoc', '<iframe title="raw" srcDoc={html} />'],
    ])(
        'Given_a_component_using_%s_When_linted_Then_lint_fails_naming_it',
        async (_label, sink, element) => {
            const messages = await restrictedSyntaxIn(componentRendering(element), COMPONENT_FILE);

            expect(messages).toHaveLength(1);
            expect(messages[0]).toContain(sink);
        },
    );

    it.each([
        ['innerHTML', 'innerHTML', 'element.innerHTML = html;'],
        ['outerHTML', 'outerHTML', 'element.outerHTML = html;'],
        ['a_string_keyed_innerHTML', 'innerHTML', "element['innerHTML'] = html;"],
        ['a_template_keyed_innerHTML', 'innerHTML', 'element[`innerHTML`] = html;'],
        ['a_template_keyed_outerHTML', 'outerHTML', 'element[`outerHTML`] = html;'],
        [
            'a_createElement_prop',
            'dangerouslySetInnerHTML',
            "createElement('div', { dangerouslySetInnerHTML: { __html: html } });",
        ],
        [
            'a_string_keyed_createElement_prop',
            'dangerouslySetInnerHTML',
            "createElement('div', { 'dangerouslySetInnerHTML': { __html: html } });",
        ],
        ['Object_assign_innerHTML', 'innerHTML', 'Object.assign(element, { innerHTML: html });'],
        [
            'Object_assign_string_keyed_outerHTML',
            'outerHTML',
            "Object.assign(element, { 'outerHTML': html });",
        ],
        [
            'insertAdjacentHTML',
            'insertAdjacentHTML',
            "element.insertAdjacentHTML('beforeend', html);",
        ],
        [
            'a_string_keyed_insertAdjacentHTML',
            'insertAdjacentHTML',
            "element['insertAdjacentHTML']('beforeend', html);",
        ],
        [
            'a_template_keyed_insertAdjacentHTML',
            'insertAdjacentHTML',
            "element[`insertAdjacentHTML`]('beforeend', html);",
        ],
        [
            'createContextualFragment',
            'createContextualFragment',
            'document.createRange().createContextualFragment(html);',
        ],
        ['setHTMLUnsafe', 'setHTMLUnsafe', 'element.setHTMLUnsafe(html);'],
        [
            'parseFromString',
            'parseFromString',
            "new DOMParser().parseFromString(html, 'text/html');",
        ],
        ['document_write', 'document.write', 'document.write(html);'],
        ['document_writeln', 'document.writeln', 'document.writeln(html);'],
        ['window_document_write', 'document.write', 'window.document.write(html);'],
        ['a_string_keyed_document_write', 'document.write', "document['write'](html);"],
    ])(
        'Given_a_module_writing_raw_HTML_through_%s_When_linted_Then_lint_fails_naming_it',
        async (_label, sink, statement) => {
            const messages = await restrictedSyntaxIn(moduleRunning(statement), MODULE_FILE);

            expect(messages).toHaveLength(1);
            expect(messages[0]).toContain(sink);
        },
    );

    it('Given_a_module_reading_innerHTML_When_linted_Then_no_restricted_syntax_is_reported', async () => {
        // Reading markup back is how a test inspects what React rendered; only a write hands
        // a string to the HTML parser.
        const messages = await restrictedSyntaxIn(
            moduleRunning('console.info(element.innerHTML, html);'),
            MODULE_FILE,
        );

        expect(messages).toEqual([]);
    });

    it('Given_Object_assign_copying_other_members_When_linted_Then_no_restricted_syntax_is_reported', async () => {
        // Only an innerHTML or outerHTML member reaches the HTML parser; copying any other
        // member onto an element is how a test or a component sets a plain attribute.
        const messages = await restrictedSyntaxIn(
            moduleRunning('Object.assign(element, { title: html, id: html });'),
            MODULE_FILE,
        );

        expect(messages).toEqual([]);
    });

    it('Given_a_module_writing_to_a_stream_When_linted_Then_no_restricted_syntax_is_reported', async () => {
        // Only `document`'s own write parses markup; a writer of the same name elsewhere does not.
        const messages = await restrictedSyntaxIn(
            moduleRunning('new WritableStream().getWriter().write(html);'),
            MODULE_FILE,
        );

        expect(messages).toEqual([]);
    });

    it('Given_a_test_file_writing_innerHTML_When_linted_Then_lint_fails_beside_the_skipped_test_rule', async () => {
        // Test files configure `no-restricted-syntax` again for `.skip`, `.todo` and `.only`,
        // and a later setting of a rule replaces an earlier one, so this proves the raw-HTML
        // selectors survive that replacement.
        const messages = await restrictedSyntaxIn(
            "document.body.innerHTML = '<p>stub</p>';\nit.skip('a test', () => undefined);\n",
            TEST_FILE,
        );

        expect(messages).toHaveLength(2);
        expect(messages.filter((message) => message.includes('innerHTML'))).toHaveLength(1);
        expect(messages.filter((message) => message.includes('.skip'))).toHaveLength(1);
    });
});
