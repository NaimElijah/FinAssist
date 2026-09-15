// @vitest-environment node
// Reads index.html and public/ from disk, so it needs real filesystem paths; under jsdom
// `import.meta.url` is an http: URL and fileURLToPath rejects it.
/**
 * The page shell names its icon, and the icon is a file Vite copies into the build.
 *
 * A page that declares no icon makes the browser request `/favicon.ico`, and the 404 it gets back
 * is a console error on first load: noise in the one place the client's own logs are read
 * (ADR-019).
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const WEB_ROOT = fileURLToPath(new URL('..', import.meta.url));

/** Vite serves `public/` at the site root and copies it verbatim into `dist/`. */
const PUBLIC_DIR = join(WEB_ROOT, 'public');

const ICON_HREF = /<link[^>]*\brel="icon"[^>]*\bhref="\/([^"]+)"/;

describe('the page icon', () => {
    it('Given_the_page_shell_When_its_icon_link_is_read_Then_it_names_an_svg_under_public', () => {
        const html = readFileSync(join(WEB_ROOT, 'index.html'), 'utf8');

        const href = ICON_HREF.exec(html)?.[1];
        if (href === undefined) {
            throw new Error('index.html declares no <link rel="icon"> with a root-relative href');
        }

        const icon = join(PUBLIC_DIR, href);
        expect(existsSync(icon)).toBe(true);
        expect(readFileSync(icon, 'utf8')).toMatch(
            /^<svg\b[^>]*\bxmlns="http:\/\/www\.w3\.org\/2000\/svg"/,
        );
    });
});
