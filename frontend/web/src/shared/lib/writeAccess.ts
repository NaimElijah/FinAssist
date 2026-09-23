/**
 * Whether the signed-in session should be shown a control that changes data.
 *
 * This is a UX courtesy alone, never a security boundary: hiding a button here only spares a
 * read-only session a control that could only fail. The ledger refuses every write from a viewer
 * with 403, and the assistant grants only `portfolio:read`, regardless of what this client
 * renders (ADR-030, ADR-032) - a viewer who bypasses the UI still meets the server's own refusal.
 *
 * Mirrors planning's `AccountOptionsContext` (`src/features/planning/hooks/accountOptions.ts`):
 * `SessionGate` computes the value once, from the signed-in session's roles, and provides it
 * above every route, so a screen reads it rather than resolving it again.
 */
import { createContext, useContext } from 'react';

/** What a screen may offer, once its session's roles are known. */
export interface WriteAccess {
    /** Whether a control that changes data may be shown at all. Never enforced by a server. */
    readonly canWrite: boolean;
}

/**
 * Where a write control reads whether to render itself. `undefined` until `SessionGate` provides
 * one, so a component mounted without it fails loudly rather than defaulting to write access.
 */
export const WriteAccessContext = createContext<WriteAccess | undefined>(undefined);

/**
 * The write access `SessionGate` provides around every route.
 *
 * @returns whether the signed-in session should be shown a control that changes data.
 * @throws Error - when no `WriteAccessContext` provider is above the caller.
 */
export function useWriteAccess(): WriteAccess {
    const access = useContext(WriteAccessContext);
    if (access === undefined) {
        throw new Error(
            'useWriteAccess needs a WriteAccessContext provider; SessionGate provides one once a session has loaded',
        );
    }
    return access;
}
