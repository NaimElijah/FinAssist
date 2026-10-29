// @vitest-environment node
// Reads the source tree, so it needs real filesystem paths; under jsdom
// `import.meta.url` is an http: URL and fileURLToPath rejects it.
/**
 * Enforces the same boundary the two backend services enforce, in the third codebase.
 *
 * A feature mirrors a bounded context and must not import another feature. Composition happens
 * in `src/app`, which is the only place allowed to know about every feature at once. Without
 * this rule, `src/features` is a naming convention rather than a boundary. Inside a feature,
 * dependencies point inward through its layers, and only its `api/` sees a generated contract.
 *
 * The backend counterparts are `LedgerArchitectureTest` (ArchUnit) and the assistant's
 * `tests/test_architecture.py`. See docs/ADRs/ADR-010-bounded-context-map.md.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const SRC = fileURLToPath(new URL('../src', import.meta.url));
const FEATURES_DIR = join(SRC, 'features');
const APP_DIR = join(SRC, 'app');

function sourceFilesUnder(dir: string): string[] {
    return readdirSync(dir).flatMap((entry) => {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) return sourceFilesUnder(full);
        return /\.tsx?$/.test(entry) ? [full] : [];
    });
}

/**
 * Calls whose first argument names a module. `vi.mock` and its siblings carry no `import` token,
 * yet couple a test to the module they name as firmly as an import does.
 */
const MODULE_NAMING_CALLS = new Set([
    'require',
    'vi.mock',
    'vi.doMock',
    'vi.importActual',
    'vi.importMock',
]);

/** The modules a source file names: each literal specifier, and each one computed at run time. */
interface ModuleReferences {
    readonly specifiers: readonly string[];
    readonly computed: readonly string[];
}

/** The text of a string literal or a template literal without substitutions, else undefined. */
function literalText(node: ts.Node | undefined): string | undefined {
    return node !== undefined && ts.isStringLiteralLike(node) ? node.text : undefined;
}

/**
 * Whether a node is `import()` called with a literal specifier. Vitest's type-safe form,
 * `vi.mock(import('./x'), ...)`, passes one as the module: the visit records its specifier when
 * it reaches the inner call, so the outer argument is not computed.
 */
function isLiteralDynamicImport(node: ts.Node | undefined): boolean {
    return (
        node !== undefined &&
        ts.isCallExpression(node) &&
        node.expression.kind === ts.SyntaxKind.ImportKeyword &&
        literalText(node.arguments[0]) !== undefined
    );
}

/**
 * Each file's references, parsed once: every rule below rescans the tree, and the sources do not
 * change while the file runs.
 */
const parsedReferences = new Map<string, ModuleReferences>();

/**
 * Every module a source file names, read from TypeScript's own parse tree: static imports and
 * `import type`, re-exports (`export ... from`), `import x = require(...)`, `typeof import(...)`,
 * a dynamic `import()`, and a call in `MODULE_NAMING_CALLS`.
 *
 * A parser rather than a pattern, so a specifier written as a template literal is still found,
 * and one inside a comment or a string - including after a `/*` that a string happens to
 * contain - is not. A call whose specifier is computed, which no rule here can resolve, is
 * listed under `computed` instead.
 */
function moduleReferences(file: string): ModuleReferences {
    const cached = parsedReferences.get(file);
    if (cached !== undefined) return cached;

    const source = ts.createSourceFile(
        file,
        readFileSync(file, 'utf8'),
        ts.ScriptTarget.Latest,
        false,
        file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
    );
    const specifiers: string[] = [];
    const computed: string[] = [];

    const visit = (node: ts.Node): void => {
        let named: ts.Node | undefined;
        let call: string | undefined;
        if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) {
            named = node.moduleSpecifier;
        } else if (
            ts.isImportEqualsDeclaration(node) &&
            ts.isExternalModuleReference(node.moduleReference)
        ) {
            named = node.moduleReference.expression;
        } else if (ts.isImportTypeNode(node) && ts.isLiteralTypeNode(node.argument)) {
            named = node.argument.literal;
        } else if (ts.isCallExpression(node)) {
            const callee =
                node.expression.kind === ts.SyntaxKind.ImportKeyword
                    ? 'import'
                    : node.expression.getText(source);
            if (callee === 'import' || MODULE_NAMING_CALLS.has(callee)) {
                call = callee;
                named = node.arguments[0];
            }
        }

        const specifier = literalText(named);
        if (specifier !== undefined) {
            specifiers.push(specifier);
        } else if (call !== undefined && !isLiteralDynamicImport(named)) {
            computed.push(`${call}(${named?.getText(source) ?? ''})`);
        }
        ts.forEachChild(node, visit);
    };
    visit(source);

    const references = { specifiers, computed };
    parsedReferences.set(file, references);
    return references;
}

function importSpecifiers(file: string): readonly string[] {
    return moduleReferences(file).specifiers;
}

/** The feature a path belongs to, or undefined when it is not inside a feature. */
function owningFeature(file: string): string | undefined {
    const rel = relative(FEATURES_DIR, file);
    if (rel.startsWith('..')) return undefined;
    return rel.split(sep)[0];
}

/** The feature an import specifier reaches into, if any. */
function targetFeature(specifier: string, fromFile: string): string | undefined {
    const aliased = /^@\/features\/([^/]+)/.exec(specifier);
    if (aliased) return aliased[1];

    if (specifier.startsWith('.')) {
        const resolved = join(fromFile, '..', specifier);
        return owningFeature(resolved);
    }
    return undefined;
}

const features = readdirSync(FEATURES_DIR).filter((entry) =>
    statSync(join(FEATURES_DIR, entry)).isDirectory(),
);

describe('feature boundaries', () => {
    it('Given_src_When_its_sources_are_read_Then_no_module_is_named_by_a_computed_specifier', () => {
        // Every rule in this file resolves a specifier to a path. One built at run time, such as
        // import(`@/features/${name}`), names a module none of them can see, so none is allowed.
        const offences = sourceFilesUnder(SRC).flatMap((file) =>
            moduleReferences(file).computed.map((call) => `${relative(SRC, file)}: ${call}`),
        );

        expect(offences, 'name the module with a literal specifier').toEqual([]);
    });

    it('Given_the_features_directory_When_listed_Then_it_is_not_empty', () => {
        // Guards the rules below from passing vacuously, the way an empty package would.
        expect(features.length).toBeGreaterThan(0);
    });

    // A plain loop rather than it.each: one named test per feature, and no tuple gymnastics
    // to satisfy it.each's signature.
    for (const feature of features) {
        it(`Given_feature_${feature}_When_its_sources_are_read_Then_it_imports_no_other_feature`, () => {
            const offences: string[] = [];

            for (const file of sourceFilesUnder(join(FEATURES_DIR, feature))) {
                for (const specifier of importSpecifiers(file)) {
                    const target = targetFeature(specifier, file);
                    if (target !== undefined && target !== feature) {
                        offences.push(`${relative(SRC, file)} -> ${specifier}`);
                    }
                }
            }

            expect(
                offences,
                `feature '${feature}' reaches into another feature. Compose in src/app instead.`,
            ).toEqual([]);
        });
    }

    it('Given_shared_When_its_sources_are_read_Then_it_imports_no_feature', () => {
        const offences: string[] = [];

        for (const file of sourceFilesUnder(join(SRC, 'shared'))) {
            for (const specifier of importSpecifiers(file)) {
                if (specifier.includes('features/')) {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        expect(offences, 'shared is the kernel; it may not depend on a feature').toEqual([]);
    });
});

/**
 * The absolute path a specifier names inside the source tree, or undefined for a package.
 *
 * Resolves the `@/` alias as vite.config.ts defines it, and a relative specifier against the
 * importing file's directory. No extension is added: the rules below compare directories and
 * file stems, which `./routes` and `./routes.tsx` share.
 */
function resolveInSrc(specifier: string, fromFile: string): string | undefined {
    if (specifier.startsWith('@/')) return join(SRC, specifier.slice('@/'.length));
    if (specifier.startsWith('.')) return join(fromFile, '..', specifier);
    return undefined;
}

/** Whether `path` is `dir` itself or lies anywhere inside it. */
function isWithin(dir: string, path: string): boolean {
    const rel = relative(dir, path);
    return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel));
}

/** Whether a path inside `src/features` is a feature's public entry: its directory or `index`. */
function isFeatureEntry(path: string): boolean {
    const [feature = '', ...rest] = relative(FEATURES_DIR, path).split(sep);
    if (feature === '') return false;
    return rest.length === 0 || (rest.length === 1 && /^index(\.tsx?)?$/.test(rest[0] ?? ''));
}

describe('composition direction', () => {
    it('Given_src_app_When_its_sources_are_read_Then_it_imports_a_feature_only_through_its_index', () => {
        const offences: string[] = [];
        let featureImports = 0;

        for (const file of sourceFilesUnder(APP_DIR)) {
            for (const specifier of importSpecifiers(file)) {
                const resolved = resolveInSrc(specifier, file);
                if (resolved === undefined || !isWithin(FEATURES_DIR, resolved)) continue;
                featureImports += 1;
                if (!isFeatureEntry(resolved)) {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        // Guards the rule from passing vacuously. src/app composes every feature's routes, so a
        // scan that finds no feature import at all has stopped reading imports, not found none.
        expect(featureImports, 'src/app imports no feature at all').toBeGreaterThan(0);
        expect(
            offences,
            "src/app reaches past a feature's index.ts. Export what it needs from the index.",
        ).toEqual([]);
    });

    it('Given_the_features_When_their_sources_are_read_Then_none_imports_src_app', () => {
        const offences: string[] = [];
        let importsResolvedInSrc = 0;

        for (const file of sourceFilesUnder(FEATURES_DIR)) {
            for (const specifier of importSpecifiers(file)) {
                const resolved = resolveInSrc(specifier, file);
                if (resolved === undefined) continue;
                importsResolvedInSrc += 1;
                if (isWithin(APP_DIR, resolved)) {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        // Guards the rule from passing vacuously, as the rule above does. Every feature imports
        // src/shared, so a scan that resolves no import inside src has stopped resolving
        // specifiers - and could no longer see an import of src/app either - not found none.
        expect(
            importsResolvedInSrc,
            'no import under src/features resolved inside src',
        ).toBeGreaterThan(0);
        expect(
            offences,
            'a feature depends on the app that composes it. Pass what it needs in from src/app.',
        ).toEqual([]);
    });

    it('Given_src_shared_When_its_sources_are_read_Then_none_imports_src_app', () => {
        // src/app composes src/shared, so shared reaching back into it would close a cycle, and
        // src/app is the composition root nothing may import back into.
        const offences: string[] = [];
        let importsResolvedInSrc = 0;

        for (const file of sourceFilesUnder(join(SRC, 'shared'))) {
            for (const specifier of importSpecifiers(file)) {
                const resolved = resolveInSrc(specifier, file);
                if (resolved === undefined) continue;
                importsResolvedInSrc += 1;
                if (isWithin(APP_DIR, resolved)) {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        // Guards the rule from passing vacuously: shared's modules import one another, so a scan
        // that resolves no import inside src has stopped resolving specifiers.
        expect(
            importsResolvedInSrc,
            'no import under src/shared resolved inside src',
        ).toBeGreaterThan(0);
        expect(
            offences,
            'src/shared depends on the app that composes it. Pass what it needs in instead.',
        ).toEqual([]);
    });
});

/**
 * A feature's layers, innermost first. A module may import its own layer or one listed before
 * it, never one after: `types` holds the feature's own model and depends on nothing in the
 * feature, `api` maps the wire onto those types, `hooks` wraps `api` in queries and mutations,
 * and `components` renders them. The feature's root files (`index.ts`, `routes.tsx`) sit above
 * every layer, since they compose them.
 */
const LAYERS = ['types', 'api', 'hooks', 'components'] as const;
type Layer = (typeof LAYERS)[number];

function isLayer(name: string): name is Layer {
    return (LAYERS as readonly string[]).includes(name);
}

/**
 * Where a path inside `src/features` sits: its feature, and its layer or `'root'` for a file
 * directly in the feature's directory. Undefined outside `src/features`.
 *
 * A specifier naming a layer's directory itself, such as `../types`, resolves to that layer; one
 * naming the feature's directory, such as `..` or `@/features/<feature>`, resolves to its root.
 */
function featurePlace(path: string): { feature: string; layer: Layer | 'root' } | undefined {
    if (!isWithin(FEATURES_DIR, path)) return undefined;
    const [feature = '', second = '', ...rest] = relative(FEATURES_DIR, path).split(sep);
    if (feature === '') return undefined;
    if (second === '') return { feature, layer: 'root' };
    if (isLayer(second)) return { feature, layer: second };
    return rest.length === 0 ? { feature, layer: 'root' } : undefined;
}

/**
 * Every import from one layer of `feature` to another of the same feature, counted, and those
 * that reach outward listed as offences: to a later layer, or, from production code, to the
 * feature's root. A test may import the root, since it drives its screen through the routes the
 * feature's index exports, the way the app does.
 */
function crossLayerImportsOf(feature: string): { count: number; offences: string[] } {
    const offences: string[] = [];
    let count = 0;

    for (const file of sourceFilesUnder(join(FEATURES_DIR, feature))) {
        const from = featurePlace(file);
        if (from === undefined || from.layer === 'root') continue;
        for (const specifier of importSpecifiers(file)) {
            const resolved = resolveInSrc(specifier, file);
            const to = resolved === undefined ? undefined : featurePlace(resolved);
            if (to?.feature !== feature || to.layer === from.layer) continue;
            count += 1;
            const reachesRoot = to.layer === 'root' && !isTestFile(file);
            const reachesOutward =
                to.layer !== 'root' && LAYERS.indexOf(to.layer) > LAYERS.indexOf(from.layer);
            if (reachesRoot || reachesOutward) {
                offences.push(`${relative(SRC, file)} -> ${specifier}`);
            }
        }
    }
    return { count, offences };
}

const GENERATED_CONTRACTS_DIR = join(SRC, 'shared', 'api', 'contracts');
const SHARED_UI_DIR = join(SRC, 'shared', 'ui');

describe('layers inside a feature', () => {
    it('Given_each_feature_When_its_directories_are_listed_Then_each_is_a_known_layer', () => {
        // The layer rule below ranks only the directories it knows. A new directory would sit
        // outside it, unranked and unchecked, so it must be placed in LAYERS first.
        const unknown = features.flatMap((feature) =>
            readdirSync(join(FEATURES_DIR, feature))
                .filter((entry) => statSync(join(FEATURES_DIR, feature, entry)).isDirectory())
                .filter((entry) => !isLayer(entry))
                .map((entry) => `${feature}/${entry}`),
        );

        expect(unknown, `rank each directory in LAYERS (${LAYERS.join(', ')})`).toEqual([]);
    });

    for (const feature of features) {
        it(`Given_feature_${feature}_When_its_layers_are_read_Then_each_imports_only_the_layers_below_it`, () => {
            expect(
                crossLayerImportsOf(feature).offences,
                `a layer of '${feature}' imports one above it (order: ${LAYERS.join(' < ')}).`,
            ).toEqual([]);
        });
    }

    it('Given_the_features_When_their_layers_are_read_Then_some_layer_imports_another', () => {
        // Guards the rule above from passing vacuously. Every feature's components use its hooks
        // and types, so a scan that counts no import between two layers has stopped resolving
        // specifiers, not found none.
        const count = features.reduce(
            (sum, feature) => sum + crossLayerImportsOf(feature).count,
            0,
        );

        expect(count, 'no import between two layers of a feature').toBeGreaterThan(0);
    });

    it('Given_the_features_When_their_sources_are_read_Then_only_a_features_api_imports_a_generated_contract_type', () => {
        // A feature's api/ is its anti-corruption layer (ADR-029): it maps each generated wire
        // type onto the feature's own, so a contract change stops there instead of rippling into
        // every screen that would otherwise name the wire shape.
        const offences: string[] = [];
        let apiImports = 0;

        for (const file of sourceFilesUnder(FEATURES_DIR)) {
            for (const specifier of importSpecifiers(file)) {
                const resolved = resolveInSrc(specifier, file);
                if (resolved === undefined || !isWithin(GENERATED_CONTRACTS_DIR, resolved))
                    continue;
                if (featurePlace(file)?.layer === 'api') {
                    apiImports += 1;
                } else {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        // Guards the rule from passing vacuously: every feature with a backend types its responses
        // with the generated contract, so a scan that finds no api/ importing one has gone blind.
        expect(apiImports, "no feature's api/ imports a generated contract type").toBeGreaterThan(
            0,
        );
        expect(
            offences,
            "a generated contract type is used outside a feature's api/. Map it there instead.",
        ).toEqual([]);
    });

    it('Given_the_features_When_their_types_are_read_Then_none_imports_src_shared_ui', () => {
        // The layer rule ranks only a feature's own layers, so any layer may import src/shared.
        // src/shared/ui re-exports React components, though, and types/ is the feature's model:
        // the innermost layer must not depend on presentation (ADR-029). A shared value both
        // need, such as DISPLAY_LOCALE, lives in src/shared/lib instead.
        const offences: string[] = [];
        let importsResolvedInSrc = 0;

        for (const file of sourceFilesUnder(FEATURES_DIR)) {
            if (featurePlace(file)?.layer !== 'types') continue;
            for (const specifier of importSpecifiers(file)) {
                const resolved = resolveInSrc(specifier, file);
                if (resolved === undefined) continue;
                importsResolvedInSrc += 1;
                if (isWithin(SHARED_UI_DIR, resolved)) {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        // Guards the rule from passing vacuously: a feature's types import one another, so a
        // scan that resolves no import inside src has stopped resolving specifiers.
        expect(
            importsResolvedInSrc,
            "no import under a feature's types/ resolved inside src",
        ).toBeGreaterThan(0);
        expect(
            offences,
            "a feature's types/ imports the UI kit. Move what it needs to src/shared/lib.",
        ).toEqual([]);
    });
});

const TESTING_DIR = join(SRC, 'shared', 'testing');

/** Whether a source file is a test: `*.test.ts` or `*.test.tsx`. */
function isTestFile(file: string): boolean {
    return /\.test\.tsx?$/.test(file);
}

describe('test helpers', () => {
    // src/shared/testing imports vitest and stubs globals. Reached from production code, it would
    // either break the build or ship a test double to the browser.
    it('Given_src_When_its_sources_are_read_Then_only_test_files_import_src_shared_testing', () => {
        const offences: string[] = [];
        let testFileImports = 0;

        for (const file of sourceFilesUnder(SRC)) {
            // The helpers may use one another; the rule is about who else reaches them.
            if (isWithin(TESTING_DIR, file)) continue;
            for (const specifier of importSpecifiers(file)) {
                const resolved = resolveInSrc(specifier, file);
                if (resolved === undefined || !isWithin(TESTING_DIR, resolved)) continue;
                if (isTestFile(file)) {
                    testFileImports += 1;
                } else {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        // Guards the rule from passing vacuously: the component tests all use the helpers, so a
        // scan that sees no test importing them has stopped resolving specifiers.
        expect(testFileImports, 'no test file imports src/shared/testing').toBeGreaterThan(0);
        expect(
            offences,
            'production code reaches src/shared/testing. Only *.test.ts(x) files may import it.',
        ).toEqual([]);
    });
});
