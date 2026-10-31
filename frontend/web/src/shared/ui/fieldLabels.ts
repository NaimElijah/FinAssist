/**
 * How a form tells `ErrorState` the labels of the fields a problem's `errors` names.
 *
 * A service names a field by its wire path, such as `entries[0].amount`; the owner knows it by the
 * label beside it, such as "Debit 1". Kept apart from `ErrorState` so that file exports only its
 * component.
 */

/**
 * The label a form shows for a field a problem's `errors` names by its wire path, such as
 * "Debit 1 or Credit 1" for `entries[0].amount`, or `undefined` for a path the form does not show.
 */
export type FieldLabelLookup = (field: string) => string | undefined;

/**
 * A `FieldLabelLookup` over a fixed table of wire paths and labels.
 *
 * Only the table's own keys are read, so a path such as `constructor` is never mistaken for a
 * label inherited from `Object.prototype`.
 *
 * @param labels - each wire path the form's request can be refused on, and the field's label.
 * @returns the label for a listed path, otherwise `undefined`.
 */
export function labelsByField(labels: Readonly<Record<string, string>>): FieldLabelLookup {
    return (field) => (Object.hasOwn(labels, field) ? labels[field] : undefined);
}
