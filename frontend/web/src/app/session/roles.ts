/**
 * The realm roles the web client reads from a session, named once (ADR-032's role table).
 */

/** The realm role ADR-032 grants read and write permissions to. */
export const OWNER_ROLE = 'owner';

/** The realm role ADR-032 grants only read permissions to. */
export const VIEWER_ROLE = 'viewer';
