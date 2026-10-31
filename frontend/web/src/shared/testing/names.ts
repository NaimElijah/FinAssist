/**
 * Accessible-name matchers for a component test, beside Testing Library's exact strings.
 */

const REGEX_SPECIAL = /[.*+?^${}()|[\]\\]/g;

/**
 * Matches an accessible name that opens with `word` and goes on, such as a row control named
 * "Clear Rent, 1 Sept 2026" whose visible text is "Clear" (WCAG 2.5.3).
 *
 * For a lookup or an absence check that must not depend on which row it names. It never matches
 * the bare word, so a control named exactly "Clear", such as a panel's submit, is told apart.
 *
 * @param word - the control's visible text.
 * @returns a pattern for `getByRole`'s `name` option.
 */
export function nameStartingWith(word: string): RegExp {
    return new RegExp(`^${word.replace(REGEX_SPECIAL, '\\$&')} .`);
}
