// @vitest-environment node
// Reads the stylesheets from disk, so it needs real filesystem paths; under jsdom
// `import.meta.url` is an http: URL and fileURLToPath rejects it.
/**
 * Every colour pair the web client draws meets WCAG 2.2 level AA contrast in both themes: 4.5:1
 * for text (SC 1.4.3), and 3:1 for a control's edge, a chart line and the focus ring (SC 1.4.11).
 *
 * jsdom computes no styles, so no screen test can measure contrast. This reads the tokens from
 * `src/shared/ui/tokens.css` itself - the light theme from `:root`, the dark one from `:root`
 * overlaid with its `prefers-color-scheme: dark` block - and measures each pair with WCAG's
 * relative-luminance formula. Pairs come from two places: every rule that sets both a text and a
 * background colour is read from the stylesheets, so a new rule is measured without being listed;
 * a pair a rule completes only by inheritance, such as muted text on whichever surface it lands
 * on, is listed by hand in `INHERITED_PAIRS`.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC = fileURLToPath(new URL('../src', import.meta.url));
const TOKENS_FILE = join(SRC, 'shared', 'ui', 'tokens.css');
const LAYOUT_FILE = join(SRC, 'app', 'Layout.module.css');
const USER_MENU_FILE = join(SRC, 'app', 'session', 'UserMenu.module.css');

/** SC 1.4.3, Contrast (Minimum): text at normal size. */
const TEXT_MINIMUM = 4.5;
/** SC 1.4.11, Non-text Contrast: a control's boundary, a meaningful graphic, a focus indicator. */
const NON_TEXT_MINIMUM = 3;

/** A theme's colour tokens, by name without the leading `--`, each as written in the file. */
type Theme = ReadonlyMap<string, string>;

const THEME_NAMES = ['light', 'dark'] as const;
type ThemeName = (typeof THEME_NAMES)[number];

const COMMENT = /\/\*[\s\S]*?\*\//g;
const DARK_BLOCK =
    /@media\s*\(\s*prefers-color-scheme:\s*dark\s*\)\s*\{\s*:root\s*\{([^{}]*)\}\s*\}/;
const ROOT_BLOCK = /:root\s*\{([^{}]*)\}/;
const COLOUR_TOKEN = /--(color-[\w-]+)\s*:\s*([^;]+);/g;
const HEX_COLOUR = /^#[0-9a-f]{6}$/i;
/** An innermost rule, so a rule nested in an `@media` block is read as its own selector. */
const RULE = /([^{}]+)\{([^{}]*)\}/g;
/** A value that is one colour token and nothing else; a `color-mix()` is not measured here. */
const SINGLE_TOKEN = /^var\(--(color-[\w-]+)\)$/;
const TRANSPARENT = 'transparent';

function withoutComments(css: string): string {
    return css.replace(COMMENT, '');
}

function readCss(file: string): string {
    return withoutComments(readFileSync(file, 'utf8'));
}

function colourTokens(block: string): Map<string, string> {
    const tokens = new Map<string, string>();
    for (const [, name, value] of block.matchAll(COLOUR_TOKEN)) {
        if (name !== undefined && value !== undefined) {
            tokens.set(name, value.trim());
        }
    }
    return tokens;
}

function readThemes(): Record<ThemeName, Theme> {
    const css = readCss(TOKENS_FILE);
    const darkBlock = DARK_BLOCK.exec(css)?.[1];
    const lightBlock = ROOT_BLOCK.exec(css.replace(DARK_BLOCK, ''))?.[1];
    if (darkBlock === undefined || lightBlock === undefined) {
        throw new Error(
            'tokens.css no longer has a :root block and a prefers-color-scheme: dark :root block',
        );
    }
    const light = colourTokens(lightBlock);
    return { light, dark: new Map([...light, ...colourTokens(darkBlock)]) };
}

function hexOf(theme: Theme, token: string): string {
    const value = theme.get(token);
    if (value === undefined) {
        throw new Error(`tokens.css defines no --${token}`);
    }
    if (!HEX_COLOUR.test(value)) {
        throw new Error(`--${token} is '${value}', which this test can only measure as #rrggbb`);
    }
    return value;
}

/** WCAG 2.2's relative luminance of an sRGB colour written `#rrggbb`. */
function relativeLuminance(hex: string): number {
    const [red = 0, green = 0, blue = 0] = [1, 3, 5].map((start) => {
        const channel = parseInt(hex.slice(start, start + 2), 16) / 255;
        return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

function contrastRatio(theme: Theme, foreground: string, background: string): number {
    const luminances = [foreground, background].map((token) =>
        relativeLuminance(hexOf(theme, token)),
    );
    return (Math.max(...luminances) + 0.05) / (Math.min(...luminances) + 0.05);
}

interface Rule {
    readonly selector: string;
    readonly body: string;
}

function rulesOf(css: string): Rule[] {
    return [...css.matchAll(RULE)].map(([, selector = '', body = '']) => ({
        selector: selector.trim(),
        body,
    }));
}

/**
 * The value of one property declared in a rule's body. Anchored to a declaration's start, so
 * asking for `color` never matches `background-color` or `border-block-end-color`.
 */
function declared(body: string, property: string): string | undefined {
    return new RegExp(`(?:^|;)\\s*${property}\\s*:\\s*([^;]+)`).exec(body)?.[1]?.trim();
}

function backgroundOf(body: string): string | undefined {
    return declared(body, 'background') ?? declared(body, 'background-color');
}

function ruleIn(file: string, selector: string): Rule {
    const rule = rulesOf(readCss(file)).find((candidate) => candidate.selector === selector);
    if (rule === undefined) {
        throw new Error(`${relative(SRC, file)} has no '${selector}' rule`);
    }
    return rule;
}

function singleToken(value: string | undefined): string | undefined {
    return value === undefined ? undefined : SINGLE_TOKEN.exec(value)?.[1];
}

interface DrawnPair {
    readonly foreground: string;
    readonly background: string;
    readonly drawnBy: string;
}

function cssFilesUnder(directory: string): string[] {
    return readdirSync(directory, { recursive: true, encoding: 'utf8' })
        .filter((path) => path.endsWith('.css'))
        .map((path) => join(directory, path));
}

/** Every rule under `src/` that sets both its text colour and its background to one token. */
function pairsSetTogether(): DrawnPair[] {
    return cssFilesUnder(SRC).flatMap((file) =>
        rulesOf(readCss(file)).flatMap(({ selector, body }) => {
            const foreground = singleToken(declared(body, 'color'));
            const background = singleToken(backgroundOf(body));
            return foreground === undefined || background === undefined
                ? []
                : [{ foreground, background, drawnBy: `${relative(SRC, file)} ${selector}` }];
        }),
    );
}

interface InheritedPair extends DrawnPair {
    readonly minimum: number;
}

/**
 * Pairs no single rule spells out: a colour drawn on whatever background an ancestor set. Kept by
 * hand, so a new surface or a new use of a tone belongs here.
 */
const INHERITED_PAIRS: readonly InheritedPair[] = [
    {
        foreground: 'color-text',
        background: 'color-surface',
        minimum: TEXT_MINIMUM,
        drawnBy: 'text on the page',
    },
    {
        foreground: 'color-text',
        background: 'color-surface-raised',
        minimum: TEXT_MINIMUM,
        drawnBy: 'text in a table, a panel or the chart',
    },
    {
        foreground: 'color-muted',
        background: 'color-surface',
        minimum: TEXT_MINIMUM,
        drawnBy: 'hints, notes and empty states on the page',
    },
    {
        foreground: 'color-muted',
        background: 'color-surface-raised',
        minimum: TEXT_MINIMUM,
        drawnBy: "a table's column headings, the chart's axis",
    },
    {
        foreground: 'color-accent',
        background: 'color-surface',
        minimum: TEXT_MINIMUM,
        drawnBy: "links on the page, the open planning tab's underline",
    },
    {
        foreground: 'color-accent',
        background: 'color-surface-raised',
        minimum: TEXT_MINIMUM,
        drawnBy: "links in a table, the chart's cash line",
    },
    {
        foreground: 'color-negative',
        background: 'color-surface',
        minimum: TEXT_MINIMUM,
        drawnBy: 'a field or group error on the page',
    },
    {
        foreground: 'color-negative',
        background: 'color-surface-raised',
        minimum: TEXT_MINIMUM,
        drawnBy: "a field error in an action panel, an invalid control's edge, the shortfall line",
    },
    {
        foreground: 'color-on-chrome',
        background: 'color-chrome',
        minimum: TEXT_MINIMUM,
        drawnBy: 'the area tabs and the signed-in name on the header band',
    },
    {
        foreground: 'color-warning',
        background: 'color-surface-raised',
        minimum: NON_TEXT_MINIMUM,
        drawnBy: "the chart's invested line",
    },
    {
        foreground: 'color-border-strong',
        background: 'color-surface',
        minimum: NON_TEXT_MINIMUM,
        drawnBy: "a control's or an empty state's edge on the page",
    },
    {
        foreground: 'color-border-strong',
        background: 'color-surface-raised',
        minimum: NON_TEXT_MINIMUM,
        drawnBy: "a control's edge in a panel, the chart's zero line",
    },
    {
        foreground: 'color-focus',
        background: 'color-surface',
        minimum: NON_TEXT_MINIMUM,
        drawnBy: 'the focus ring on the page',
    },
    {
        foreground: 'color-focus',
        background: 'color-surface-raised',
        minimum: NON_TEXT_MINIMUM,
        drawnBy: 'the focus ring in a panel or a table',
    },
    {
        foreground: 'color-focus-on-chrome',
        background: 'color-chrome',
        minimum: NON_TEXT_MINIMUM,
        drawnBy: 'the focus ring on the header band',
    },
];

function formatRatio(ratio: number): string {
    return `${ratio.toFixed(2)}:1`;
}

const themes = readThemes();

describe('colour contrast', () => {
    it('Given_the_stylesheets_When_read_Then_some_rule_sets_a_text_and_a_background_colour_together', () => {
        // Guards the scan below from passing vacuously, should the CSS stop parsing as expected.
        expect(pairsSetTogether().length).toBeGreaterThan(0);
    });

    // A plain loop rather than it.each, as in tests/architecture.test.ts: one named test per
    // theme and pair.
    for (const themeName of THEME_NAMES) {
        const theme = themes[themeName];

        it(`Given_every_rule_setting_a_text_and_a_background_colour_When_measured_in_the_${themeName}_theme_Then_each_meets_the_text_minimum`, () => {
            const offences = pairsSetTogether()
                .map((pair) => ({
                    ...pair,
                    ratio: contrastRatio(theme, pair.foreground, pair.background),
                }))
                .filter(({ ratio }) => ratio < TEXT_MINIMUM)
                .map(
                    ({ foreground, background, drawnBy, ratio }) =>
                        `${drawnBy}: --${foreground} on --${background} is ${formatRatio(ratio)}`,
                );

            expect(offences, `each needs ${String(TEXT_MINIMUM)}:1`).toEqual([]);
        });

        for (const { foreground, background, minimum, drawnBy } of INHERITED_PAIRS) {
            it(`Given_${foreground}_on_${background}_in_the_${themeName}_theme_When_measured_Then_it_meets_${String(minimum)}_to_1`, () => {
                const ratio = contrastRatio(theme, foreground, background);

                expect(ratio, `${drawnBy}: ${formatRatio(ratio)}`).toBeGreaterThanOrEqual(minimum);
            });
        }

        it(`Given_the_read_only_badge_on_the_header_band_When_measured_in_the_${themeName}_theme_Then_it_meets_the_text_minimum`, () => {
            const badge = ruleIn(USER_MENU_FILE, '.badge').body;
            const foreground = singleToken(declared(badge, 'color'));
            const ownBackground = backgroundOf(badge);
            // A badge with no background of its own shows the header band it sits in (Layout.tsx).
            const background =
                ownBackground === undefined || ownBackground === TRANSPARENT
                    ? singleToken(backgroundOf(ruleIn(LAYOUT_FILE, '.header').body))
                    : singleToken(ownBackground);
            if (foreground === undefined || background === undefined) {
                throw new Error("the badge's colour or its background is no longer one token");
            }

            const ratio = contrastRatio(theme, foreground, background);

            expect(
                ratio,
                `--${foreground} on --${background} is ${formatRatio(ratio)}`,
            ).toBeGreaterThanOrEqual(TEXT_MINIMUM);
        });
    }

    it('Given_the_open_tab_When_it_takes_keyboard_focus_Then_its_ring_is_drawn_inside_it_in_a_colour_measured_against_it', () => {
        // The open tab takes the page's colour and joins the page beneath it, so a ring drawn
        // outside it would run its lower edge over the page in the header band's ring colour.
        const ring = ruleIn(LAYOUT_FILE, ".tab[aria-current='page']:focus-visible").body;
        expect(declared(ring, 'outline-offset')).toMatch(/^-/);
        const ringColour = singleToken(declared(ring, 'outline-color'));
        const tabBackground = singleToken(
            backgroundOf(ruleIn(LAYOUT_FILE, ".tab[aria-current='page']").body),
        );
        expect([ringColour, tabBackground]).not.toContain(undefined);
        for (const themeName of THEME_NAMES) {
            expect(
                contrastRatio(themes[themeName], ringColour ?? '', tabBackground ?? ''),
                `the open tab's focus ring in the ${themeName} theme`,
            ).toBeGreaterThanOrEqual(NON_TEXT_MINIMUM);
        }
    });

    it('Given_forced_colours_When_backgrounds_are_dropped_Then_the_read_only_badge_keeps_an_edge_in_its_text_colour', () => {
        const border = declared(ruleIn(USER_MENU_FILE, '.badge').body, 'border') ?? '';

        expect(border).toMatch(/\bsolid\b/);
        expect(border).toMatch(/\bcurrentColor\b/i);
    });
});
