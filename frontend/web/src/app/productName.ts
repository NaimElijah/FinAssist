/**
 * The product's name, and the document title every screen builds from it, whether it renders
 * inside the app's layout or stands alone before one exists.
 */

/** Shown in the header, and closes every document title. */
export const PRODUCT_NAME = 'FinAssist';

/**
 * A document title for a screen: its own title followed by the product name, or the product name
 * alone when the screen has none.
 *
 * @param title - the screen's own title, such as "Sign in", without the product name.
 * @returns the document title, such as "Sign in · FinAssist".
 */
export function documentTitleFor(title: string | undefined): string {
    return title === undefined ? PRODUCT_NAME : `${title} · ${PRODUCT_NAME}`;
}
