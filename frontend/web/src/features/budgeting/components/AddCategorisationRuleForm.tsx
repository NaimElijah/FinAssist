import { type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import { ApiError, type ProblemFieldError } from '@/shared/api/client';
import { clearSettledOutcome } from '@/shared/lib/settledMutation';
import { Button, ErrorState, TextField } from '@/shared/ui';

import { useAddCategorisationRule } from '../hooks/budgetQueries';
import type { CategorisationRule } from '../types/rule';
import styles from './AddCategorisationRuleForm.module.css';

/** ADR-028 lists field `errors` only on a 400. */
const MALFORMED_REQUEST_STATUS = 400;

/**
 * Matches a control showing an error: the shared field frame marks it `aria-invalid` exactly while
 * it has one, and describes it by that error.
 */
const INVALID_CONTROL_SELECTOR = '[aria-invalid="true"]';

/** Matches the form's first field: the Fragment input, first in source order. */
const FIRST_FIELD_SELECTOR = 'input';

/** The form's fields, named as the ledger names them in a problem's `errors`. */
const FIELD_NAMES = ['fragment', 'category'] as const;

type FieldName = (typeof FIELD_NAMES)[number];

type FieldErrors = Partial<Record<FieldName, string>>;

/** A failed submission, split into what can be shown on a field and what cannot. */
interface SubmissionErrors {
    readonly fields: FieldErrors;
    /** The failure itself when no field explains it, for `ErrorState`; otherwise `null`. */
    readonly unplaced: Error | null;
}

function isFieldName(field: string): field is FieldName {
    return FIELD_NAMES.some((name) => name === field);
}

/** Between two messages for the same field; see `OpenAccountForm`. */
const FIELD_MESSAGE_SEPARATOR = '; ';

/**
 * A 400's field errors keyed by form field, or `undefined` when any names a field this form does
 * not have, so the whole problem is shown instead of part of it being dropped.
 */
function placeFieldErrors(errors: readonly ProblemFieldError[]): FieldErrors | undefined {
    const fields: FieldErrors = {};
    for (const { field, message } of errors) {
        if (!isFieldName(field)) {
            return undefined;
        }
        const earlier = fields[field];
        fields[field] =
            earlier === undefined ? message : `${earlier}${FIELD_MESSAGE_SEPARATOR}${message}`;
    }
    return fields;
}

/**
 * Where a failed submission's explanation belongs, mirroring `OpenAccountForm`'s rule: a 400
 * whose `errors` all name a field this form has goes on those fields; anything else is left for
 * `ErrorState`, which shows the problem's detail.
 */
function submissionErrors(error: Error | null): SubmissionErrors {
    if (error instanceof ApiError && error.problem !== undefined) {
        const { problem } = error;
        if (
            error.status === MALFORMED_REQUEST_STATUS &&
            problem.errors !== undefined &&
            problem.errors.length > 0
        ) {
            const fields = placeFieldErrors(problem.errors);
            if (fields !== undefined) {
                return { fields, unplaced: null };
            }
        }
    }
    return { fields: {}, unplaced: error };
}

/** What happens once a rule is added. */
export interface AddCategorisationRuleFormProps {
    /** Called with the added rule, once the rules list has been refetched to include it. */
    readonly onAdded: (rule: CategorisationRule) => void;
    /**
     * A caller-driven request to move focus to this form's first field, for when the rules list
     * could not identify the new rule to focus instead (its own refetch failed).
     */
    readonly focusRequested: boolean;
    /** Called once the requested focus has been applied, so the request can be cleared. */
    readonly onFocusRequestHandled: () => void;
}

/**
 * The form that adds a categorisation rule: a fragment to match, and the category it assigns.
 *
 * Submit is disabled while the request is pending, and a submit event arriving anyway is ignored
 * (ADR-028: no double submit). On success the form resets and `onAdded` is called. A failure is
 * shown where it can be acted on: a 400's field errors on their fields, and anything else in an
 * `ErrorState` above the form, exactly as `OpenAccountForm` does. Focus follows the failure there.
 * Editing either field clears a failure that has settled, since it answered the rule as it was sent.
 *
 * `focusRequested` moves focus to the Fragment field on demand - the fallback `CategorisationRulesPage`
 * reaches for when a rule was added but its list's own refetch failed, so no row can be identified
 * as the new one to focus instead.
 *
 * @param props - see `AddCategorisationRuleFormProps`.
 */
export function AddCategorisationRuleForm({
    onAdded,
    focusRequested,
    onFocusRequestHandled,
}: AddCategorisationRuleFormProps) {
    const headingId = useId();
    const formRef = useRef<HTMLFormElement>(null);
    const errorStateRef = useRef<HTMLDivElement>(null);
    /** Set from the moment a submit starts a request until that request settles. */
    const submitting = useRef(false);
    const [fragment, setFragment] = useState('');
    const [category, setCategory] = useState('');
    const addRule = useAddCategorisationRule();
    const failure = addRule.error;
    const errors = submissionErrors(failure);

    // Runs after the render that shows the failure, so the invalid controls and the error state
    // are already on the page; each failure is a new error object, so each one moves focus.
    useEffect(() => {
        if (failure === null) {
            return;
        }
        const invalidControl =
            formRef.current?.querySelector<HTMLElement>(INVALID_CONTROL_SELECTOR);
        (invalidControl ?? errorStateRef.current)?.focus();
    }, [failure]);

    useEffect(() => {
        if (!focusRequested) {
            return;
        }
        formRef.current?.querySelector<HTMLElement>(FIRST_FIELD_SELECTOR)?.focus();
        onFocusRequestHandled();
    }, [focusRequested, onFocusRequestHandled]);

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // The disabled button stops a click, not Enter in a field or a scripted submit, and a
        // second add is a second rule.
        if (submitting.current) {
            return;
        }
        submitting.current = true;
        addRule.mutate(
            { fragment, category },
            {
                onSuccess: (rule) => {
                    setFragment('');
                    setCategory('');
                    onAdded(rule);
                },
                onSettled: () => {
                    submitting.current = false;
                },
            },
        );
    }

    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                Add a rule
            </h2>
            {errors.unplaced === null ? null : (
                <ErrorState ref={errorStateRef} error={errors.unplaced} />
            )}
            <form ref={formRef} className={styles.form} onSubmit={submit}>
                <div className={styles.fields}>
                    <TextField
                        label="Fragment"
                        required
                        autoComplete="off"
                        value={fragment}
                        error={errors.fields.fragment}
                        onChange={(event) => {
                            clearSettledOutcome(addRule);
                            setFragment(event.target.value);
                        }}
                    />
                    <TextField
                        label="Category"
                        required
                        autoComplete="off"
                        value={category}
                        error={errors.fields.category}
                        onChange={(event) => {
                            clearSettledOutcome(addRule);
                            setCategory(event.target.value);
                        }}
                    />
                </div>
                <div>
                    <Button type="submit" pending={addRule.isPending}>
                        Add rule
                    </Button>
                </div>
            </form>
        </section>
    );
}
