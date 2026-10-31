/**
 * Accessible-description reading for a component test, where no role query can reach the control.
 */

/**
 * The description `element`'s `aria-describedby` gives it: the text of each element it names, in
 * the order named, joined by a space.
 *
 * Testing Library's `description` option on `getByRole` computes the same thing, but a native
 * month input has no implicit ARIA role, so no role query finds it; a label query and this cover
 * every field the same way. Only `aria-describedby` is read, the one source the UI kit's fields
 * use; a `title` is not.
 *
 * @param element - the described control.
 * @returns the description, or `''` when the control names no describing element.
 * @throws Error - when an id it names matches no element, which a test should never pass over.
 */
export function accessibleDescriptionOf(element: Element): string {
    const ids = (element.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean);
    return ids
        .map((id) => {
            const describing = element.ownerDocument.getElementById(id);
            if (describing === null) {
                throw new Error(`aria-describedby names "${id}", which matches no element`);
            }
            return describing.textContent.trim();
        })
        .join(' ');
}
