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
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC = fileURLToPath(new URL('../src', import.meta.url));
const FEATURES_DIR = join(SRC, 'features');

/** Matches the specifier of any static or dynamic import, and of a re-export. */
const IMPORT_SPECIFIER = /(?:from|import)\s*\(?\s*['"]([^'"]+)['"]/g;

function sourceFilesUnder(dir: string): string[] {
    return readdirSync(dir).flatMap((entry) => {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) return sourceFilesUnder(full);
        return /\.tsx?$/.test(entry) ? [full] : [];
    });
}

function importSpecifiers(file: string): string[] {
    const text = readFileSync(file, 'utf8');
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
