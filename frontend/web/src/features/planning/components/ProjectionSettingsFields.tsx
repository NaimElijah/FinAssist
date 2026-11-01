import { useId } from 'react';

import {
    DateField,
    LoadingNote,
    QueryErrorState,
    SelectField,
    type SelectOption,
    useRetryFocus,
} from '@/shared/ui';

import { useAccountOptions } from '../hooks/accountOptions';
import type { AccountOption, AccountOptionsFailure } from '../types/accountOption';
import type { ProjectionSettingsForm } from '../types/projectionSettings';
import { CADENCE_LABELS, CADENCES } from '../types/recurrence';
import groupStyles from './checkboxGroup.module.css';
import styles from './ProjectionSettingsFields.module.css';
import { toggled } from './toggled';

const STEP_OPTIONS: readonly SelectOption[] = CADENCES.map((cadence) => ({
    value: cadence,
    label: CADENCE_LABELS[cadence],
}));

/**
 * `value` as one of `allowed`.
 *
 * @throws Error - when it is not one, which a select offering only `allowed` cannot produce.
 */
function oneOf<Value extends string>(allowed: readonly Value[], value: string): Value {
    const match = allowed.find((candidate) => candidate === value);
    if (match === undefined) {
        throw new Error('A select produced a value it does not offer');
    }
    return match;
}

/**
 * The rule the two pickers share, stated once and describing every box: the ledger refuses an
 * account named on both sides (`AccountSelection.requireEachNamedOnce`) with a 422 naming its raw
 * id, so a box ticked on one side is disabled on the other. A disabled box leaves the Tab order,
 * so the rule also says how to free it.
 */
const ONE_SIDE_RULE =
    'An account counts as cash or as invested, not both; untick it in one list to use it in the ' +
    'other.';

/** The accounts could not be read: the failure, and any retry, in place of the pickers alone. */
function AccountsFailure({
    failure,
    onRetry,
}: {
    readonly failure: AccountOptionsFailure;
    /** Sends the read again, through `useRetryFocus` so a success moves focus to the pickers. */
    readonly onRetry: () => void;
}) {
    return <QueryErrorState error={failure.error} retrying={failure.retrying} onRetry={onRetry} />;
}

/**
 * One checkbox group of accounts, already filtered to `currency`, with every account the other
 * group holds disabled here.
 */
function AccountGroup({
    legend,
    currency,
    accounts,
    checked,
    checkedInOtherGroup,
    describedBy,
    ruleId,
    onToggle,
}: {
    readonly legend: string;
    readonly currency: string;
    readonly accounts: readonly AccountOption[];
    readonly checked: ReadonlySet<string>;
    /** The accounts the other group holds, which this one may not also name. */
    readonly checkedInOtherGroup: ReadonlySet<string>;
    /** The id of the hint explaining why only `currency` is offered. */
    readonly describedBy: string;
    /** The id of `ONE_SIDE_RULE`, which describes every box, a disabled one included. */
    readonly ruleId: string;
    readonly onToggle: (ref: string) => void;
}) {
    return (
        <fieldset aria-describedby={describedBy} className={groupStyles.group}>
            <legend className={groupStyles.legend}>{legend}</legend>
            {accounts.length === 0 ? (
                <p className={styles.muted}>{`No accounts in ${currency}.`}</p>
            ) : (
                <div className={groupStyles.options}>
                    {accounts.map((account) => (
                        <label key={account.ref} className={groupStyles.option}>
                            <input
                                type="checkbox"
                                checked={checked.has(account.ref)}
                                disabled={checkedInOtherGroup.has(account.ref)}
                                aria-describedby={ruleId}
                                onChange={() => {
                                    onToggle(account.ref);
                                }}
                            />
                            {account.label}
                        </label>
                    ))}
                </div>
            )}
        </fieldset>
    );
}

/** The Cash and Invested account pickers, in whichever state the account choices are in. */
function AccountPickers({
    value,
    onChange,
    currency,
    noCurrencyMessage,
    noun,
}: ProjectionSettingsFieldsProps) {
    const accountOptions = useAccountOptions();
    const hintId = useId();
    const ruleId = useId();
    const accountsFailure = accountOptions.status === 'loading' ? null : accountOptions.failure;
    // Loading counts as retrying: a retry after the accounts never arrived loads them afresh, and
    // the pickers it should focus are not on screen until that load settles.
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(
        accountsFailure?.error ?? null,
        accountOptions.status === 'loading' || accountsFailure?.retrying === true,
        () => {
            accountsFailure?.retry();
        },
    );

    if (accountOptions.status === 'loading') {
        return <LoadingNote message="Loading accounts…" />;
    }
    if (accountOptions.status === 'failed') {
        return <AccountsFailure failure={accountOptions.failure} onRetry={onRetry} />;
    }
    const failure =
        accountOptions.failure === null ? null : (
            <AccountsFailure failure={accountOptions.failure} onRetry={onRetry} />
        );
    if (currency === undefined) {
        return (
            <div ref={targetRef} tabIndex={-1} className={styles.accounts}>
                {failure}
                <p className={styles.hint}>{noCurrencyMessage}</p>
            </div>
        );
    }
    const inCurrency = accountOptions.options.filter((option) => option.currency === currency);
    return (
        <div ref={targetRef} tabIndex={-1} className={styles.accounts}>
            {failure}
            <p id={hintId} className={styles.hint}>
                {`A ${noun} covers one currency, so only accounts in ${currency} are offered.`}
            </p>
            <p id={ruleId} className={styles.hint}>
                {ONE_SIDE_RULE}
            </p>
            <div className={styles.groups}>
                <AccountGroup
                    legend="Cash accounts"
                    currency={currency}
                    accounts={inCurrency}
                    checked={value.cash}
                    checkedInOtherGroup={value.invested}
                    describedBy={hintId}
                    ruleId={ruleId}
                    onToggle={(ref) => {
                        onChange({ ...value, cash: toggled(value.cash, ref) });
                    }}
                />
                <AccountGroup
                    legend="Invested accounts"
                    currency={currency}
                    accounts={inCurrency}
                    checked={value.invested}
                    checkedInOtherGroup={value.cash}
                    describedBy={hintId}
                    ruleId={ruleId}
                    onToggle={(ref) => {
                        onChange({ ...value, invested: toggled(value.invested, ref) });
                    }}
                />
            </div>
        </div>
    );
}

/** The shared fields' state, where it changes, and which currency's accounts to offer. */
export interface ProjectionSettingsFieldsProps {
    readonly value: ProjectionSettingsForm;
    readonly onChange: (next: ProjectionSettingsForm) => void;
    /** The currency whose accounts are offered; `undefined` offers none. */
    readonly currency: string | undefined;
    /** Shown in place of the account pickers while `currency` is `undefined`, saying why. */
    readonly noCurrencyMessage: string;
    /**
     * What the fields are read into, as the hint names it when it says only one currency's
     * accounts are offered: each covers one currency (ADR-012, ADR-016).
     */
    readonly noun: 'projection' | 'comparison';
}

/**
 * The fields a projection and a comparison share: From, To, Step, and the Cash and Invested
 * account pickers, read from the account choices `src/app` provides (`useAccountOptions`).
 *
 * The pickers offer only accounts in `currency`, with a hint saying the projection or comparison
 * they open - `noun` names which - covers one currency (ADR-012, ADR-016). An account ticked in
 * one picker is disabled in the other, since the ledger refuses one named on both sides. While the
 * accounts load they say so; when the accounts cannot be read, the failure and its retry replace
 * the pickers alone, so the rest of the form still works - a projection may open with no accounts
 * at all. From and To are natively required, so the browser refuses to submit without a horizon.
 *
 * @param props - see `ProjectionSettingsFieldsProps`.
 */
export function ProjectionSettingsFields(props: ProjectionSettingsFieldsProps) {
    const { value, onChange } = props;
    return (
        <>
            <div className={styles.fields}>
                <DateField
                    label="From"
                    required
                    value={value.from}
                    onChange={(event) => {
                        onChange({ ...value, from: event.target.value });
                    }}
                />
                <DateField
                    label="To"
                    required
                    value={value.to}
                    onChange={(event) => {
                        onChange({ ...value, to: event.target.value });
                    }}
                />
                <SelectField
                    label="Step"
                    options={STEP_OPTIONS}
                    value={value.step}
                    onChange={(event) => {
                        onChange({ ...value, step: oneOf(CADENCES, event.target.value) });
                    }}
                />
            </div>
            <AccountPickers {...props} />
        </>
    );
}
