/**
 * The portfolio area's URLs, written once for its routes and its links alike.
 */
import { generatePath } from 'react-router';

/** The symbol lookup. */
export const PORTFOLIO_PATH = '/portfolio';

/** One holding's page. */
export const HOLDING_PATH = '/portfolio/:symbol';

/**
 * The URL of one holding's page.
 *
 * @param symbol - the symbol as typed. `generatePath` percent-encodes it as a single path segment,
 *   so a space or a slash in it cannot change the path.
 * @returns the path, such as `/portfolio/VOO`.
 */
export function holdingPath(symbol: string): string {
    return generatePath(HOLDING_PATH, { symbol });
}
