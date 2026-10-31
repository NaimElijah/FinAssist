import { type RefObject, useEffect, useRef } from 'react';

/** The first control in a row, which takes focus when the row before it is removed. */
const FIRST_FIELD_SELECTOR = 'input, select, textarea';

/** Where focus goes once a form row is removed, and the call that says one was. */
export interface RowRemovalFocus {
    /** For the element whose children are the rows, in order. */
    readonly rowsRef: RefObject<HTMLDivElement | null>;
    /** For the control that adds a row, which takes focus when the last row is removed. */
    readonly addRowRef: RefObject<HTMLButtonElement | null>;
    /** Records that the row at `index` is being removed; call it with the removal itself. */
    readonly onRowRemoved: (index: number) => void;
}

/**
 * Keep focus in a form whose rows can be removed.
 *
 * A row's remove button goes with its row, so focus would otherwise fall to the page body and a
 * keyboard user would start again from the top. Once the removal has rendered, focus moves to what
 * followed the removed row in tab order: the first field of the row now in its place, or the
 * add-row control when the last row went.
 *
 * Destructure what it returns: the React Compiler's lint reads any member of an object holding a
 * ref, `onRowRemoved` included, as reading that ref during render.
 *
 * @param rowCount - how many rows the form holds now; a removal is acted on once it changes.
 * @returns see `RowRemovalFocus`.
 */
export function useRowRemovalFocus(rowCount: number): RowRemovalFocus {
    const rowsRef = useRef<HTMLDivElement>(null);
    const addRowRef = useRef<HTMLButtonElement>(null);
    const removedIndex = useRef<number | null>(null);

    // Runs after the render without the removed row, so the row now in its place is on the page.
    useEffect(() => {
        const index = removedIndex.current;
        if (index === null) {
            return;
        }
        removedIndex.current = null;
        const rowInPlace = rowsRef.current?.children.item(index);
        const field = rowInPlace?.querySelector<HTMLElement>(FIRST_FIELD_SELECTOR);
        (field ?? addRowRef.current)?.focus();
    }, [rowCount]);

    return {
        rowsRef,
        addRowRef,
        onRowRemoved: (index) => {
            removedIndex.current = index;
        },
    };
}
