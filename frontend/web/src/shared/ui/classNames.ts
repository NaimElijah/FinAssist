/**
 * Join class names, skipping absent ones.
 *
 * A CSS Module lookup is typed `string | undefined` under `noUncheckedIndexedAccess`, and a
 * caller's `className` is optional, so both have to be dropped rather than printed as
 * "undefined".
 *
 * @param names - class names, any of which may be absent or empty.
 * @returns the present names, separated by single spaces.
 */
export function joinClassNames(...names: readonly (string | undefined)[]): string {
    return names.filter((name) => name !== undefined && name !== '').join(' ');
}
