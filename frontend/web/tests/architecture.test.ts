// @vitest-environment node
// Reads the source tree, so it needs real filesystem paths; under jsdom
// `import.meta.url` is an http: URL and fileURLToPath rejects it.
/**
 * Enforces the same boundary the two backend services enforce, in the third codebase.
 *
 * A feature mirrors a bounded context and must not import another feature. Composition happens
 * in `src/app`, which is the only place allowed to know about every feature at once. Without
 * this rule, `src/features` is a naming convention rather than a boundary.
 *
 * The backend counterparts are `LedgerArchitectureTest` (ArchUnit) and the assistant's
 * `tests/test_architecture.py`. See docs/ADRs/ADR-010-bounded-context-map.md.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC = fileURLToPath(new URL('../src', import.meta.url));
const FEATURES_DIR = join(SRC, 'features');
const APP_DIR = join(SRC, 'app');

/**
 * Matches the specifier of a static or dynamic import, a re-export, a `require`, or a
 * `vi.mock`. The last two carry no `from`/`import` token and would otherwise slip through.
 */
const IMPORT_SPECIFIER = /(?:from|import|require|vi\.mock)\s*\(?\s*['"]([^'"]+)['"]/g;

/**
 * Blanks out comments so a specifier mentioned in prose is not read as a dependency.
 *
 * Replacing with spaces rather than deleting keeps offsets intact, and the regex above only
 * ever reads the captured specifier, never a position.
 */
function withoutComments(text: string): string {
    return text
        .replace(/\/\*[\s\S]*?\*\//g, (match) => ' '.repeat(match.length))
        .replace(/\/\/.*/g, (match) => ' '.repeat(match.length));
}

function sourceFilesUnder(dir: string): string[] {
    return readdirSync(dir).flatMap((entry) => {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) return sourceFilesUnder(full);
        return /\.tsx?$/.test(entry) ? [full] : [];
    });
}

function importSpecifiers(file: string): string[] {
    const text = withoutComments(readFileSync(file, 'utf8'));
    return [...text.matchAll(IMPORT_SPECIFIER)].map((match) => match[1] ?? '');
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
        let specifiersRead = 0;

        for (const file of sourceFilesUnder(FEATURES_DIR)) {
            for (const specifier of importSpecifiers(file)) {
                specifiersRead += 1;
                const resolved = resolveInSrc(specifier, file);
                if (resolved !== undefined && isWithin(APP_DIR, resolved)) {
                    offences.push(`${relative(SRC, file)} -> ${specifier}`);
                }
            }
        }

        expect(specifiersRead, 'no import was read under src/features').toBeGreaterThan(0);
        expect(
            offences,
            'a feature depends on the app that composes it. Pass what it needs in from src/app.',
        ).toEqual([]);
    });
});
