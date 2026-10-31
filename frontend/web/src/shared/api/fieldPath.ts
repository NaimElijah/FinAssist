/**
 * Reading the field paths a problem's `errors` names (ADR-028).
 *
 * The ledger names a body member by its dotted path and an element of a list by its zero-based
 * index in brackets, such as `entries[0].amount`; a form shows that element as a numbered row,
 * such as "Debit 1". The assistant's requests carry no list, so only the bracket form is read.
 */

/** One element of a request's list, as a problem's field path names it. */
export interface IndexedField {
    /** The element's place in the list counted from one, as a form numbers its rows. */
    readonly position: number;
    /** The path within the element, such as `amount` or `limit.amount`; absent for the element. */
    readonly member: string | undefined;
}

/** `name[index]`, then optionally `.member`. */
const INDEXED_PATH = /^(?<collection>[^.[\]]+)\[(?<index>\d+)\](?:\.(?<member>.+))?$/;

/**
 * The element of `collection` that `field` names, or `undefined` when it names none of its
 * elements - the list itself, another list, or a member outside any list.
 *
 * @param field - a field path from a problem's `errors`, such as `entries[0].amount`.
 * @param collection - the request member holding the list, such as `entries`.
 * @returns the element's one-based position and the path within it.
 */
export function indexedField(field: string, collection: string): IndexedField | undefined {
    const groups = INDEXED_PATH.exec(field)?.groups;
    if (groups?.collection !== collection || groups.index === undefined) {
        return undefined;
    }
    return { position: Number(groups.index) + 1, member: groups.member };
}
