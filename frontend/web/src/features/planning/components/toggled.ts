/**
 * `set` with `value` added when absent, or removed when present - always a new set, never the one
 * given, so a React state updater calling it stays pure. Shared by every planning checkbox group.
 *
 * @param set - the current selection.
 * @param value - the member to add or remove.
 * @returns a new set with `value`'s membership flipped.
 */
export function toggled(set: ReadonlySet<string>, value: string): ReadonlySet<string> {
    const next = new Set(set);
    if (next.has(value)) {
        next.delete(value);
    } else {
        next.add(value);
    }
    return next;
}
