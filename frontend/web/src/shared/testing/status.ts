/**
 * What the page's status regions say, for a component test asserting that a loading note is
 * announced rather than merely shown.
 */
import { screen } from '@testing-library/react';

/**
 * The text of every `role="status"` region on the page, in document order.
 *
 * @returns each region's text content; an empty string for a region with nothing in it yet.
 */
export function statusMessages(): string[] {
    return screen.queryAllByRole('status').map((region) => region.textContent);
}
