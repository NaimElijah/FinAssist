/**
 * The one place a signed-in session's realm roles become a write-access hint for the browser.
 *
 * This is a UX courtesy alone, never a security boundary - see `src/shared/lib/writeAccess.ts`'s
 * own doc comment for the boundary this crosses. Only an owner writes (ADR-032's role table); a
 * viewer, the assistant's service-principal role, an unknown role and no role at all are all
 * read-only here, but that is this client's own opinion. The ledger and the assistant decide
 * access for themselves, from the token alone, and refuse a write from anyone but an owner
 * regardless of what this function returns.
 */
import type { WriteAccess } from '@/shared/lib/writeAccess';

/** The one realm role ADR-032 grants both read and write permissions to. */
const OWNER_ROLE = 'owner';

/**
 * The write access a session with `roles` should be shown.
 *
 * @param roles - the signed-in session's realm roles, exactly as the gateway reported them.
 * @returns `{canWrite: true}` only when `roles` includes `owner`; `{canWrite: false}` for a
 *   viewer, the assistant role, an unrecognised role, or no role at all.
 */
export function writeAccessFor(roles: readonly string[]): WriteAccess {
    return { canWrite: roles.includes(OWNER_ROLE) };
}
