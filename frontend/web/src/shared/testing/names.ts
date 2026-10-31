/**
 * Accessible-name matchers for a component test, beside Testing Library's exact strings.
 */

const REGEX_SPECIAL = /[.*+?^${}()|[\]\\]/g;

/** `word` with every character a pattern would read as syntax escaped, so it matches literally. */
function escaped(word: string): string {
    return word.replace(REGEX_SPECIAL, '\\$&');
}

/**
 * Matches an accessible name that opens with `word` and goes on, such as a row control named
 * "Clear Rent, 1 Sept 2026" whose visible text is "Clear" (WCAG 2.5.3).
 *
 * For a lookup that must not depend on which row it names. It never matches the bare word, so a
 * control named exactly "Clear", such as a panel's submit, is told apart. An absence check takes
 * `nameIsOrStartsWith` instead.
 *
 * @param word - the control's visible text.
 * @returns a pattern for `getByRole`'s `name` option.
 */
export function nameStartingWith(word: string): RegExp {
    return new RegExp(`^${escaped(word)} .`);
}

/**
 * Matches an accessible name that is `word` alone or opens with it and goes on: both a panel's
 * "Clear" submit and a row's "Clear Rent, 1 Sept 2026", but not "Clearance".
 *
 * For an absence check, such as a read-only session offering no lifecycle action: matching only
 * the row-named form would pass if the control were offered under its bare word.
 *
 * @param word - the control's visible text.
 * @returns a pattern for `queryByRole`'s `name` option.
 */
export function nameIsOrStartsWith(word: string): RegExp {
    return new RegExp(`^${escaped(word)}(?: |$)`);
}
