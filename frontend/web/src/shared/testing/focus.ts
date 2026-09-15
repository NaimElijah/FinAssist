/**
 * Where focus rests, for a component test asserting where it lands once new content replaces the
 * element that held it.
 */

/**
 * Whether the focused element is `content` or holds it.
 *
 * The page body never counts, although it holds everything: it is where focus falls when the
 * element holding it is removed, which is exactly the outcome such a test must tell apart.
 *
 * @param content - the element focus should rest on or around.
 * @returns whether focus rests on `content`, or on an element other than the body containing it.
 */
export function focusedElementHolds(content: Element): boolean {
    const focused = document.activeElement;
    return focused !== null && focused !== document.body && focused.contains(content);
}
