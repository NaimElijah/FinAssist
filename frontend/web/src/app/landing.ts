/**
 * Where the app opens. Its own module, so `routes.tsx`, which redirects the root path here, and
 * `NotFound`, which links back here, share it without importing each other.
 */

/** Where the root path sends the owner: the books come first. */
export const LANDING_PATH = '/accounts';
