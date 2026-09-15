/**
 * The horizon, step and opening-account fields the projection and comparison forms share, as the
 * forms hold them, and their mapping to what the ledger is asked.
 */
import type { AccountOption } from './accountOption';
import type { ProjectionSettings } from './projection';
import type { Cadence } from './recurrence';

/** The shared fields' raw state: dates as their inputs hold them, and the accounts checked. */
export interface ProjectionSettingsForm {
    /** An ISO `YYYY-MM-DD` date, or empty until entered. */
    readonly from: string;
    /** An ISO `YYYY-MM-DD` date, or empty until entered. */
    readonly to: string;
    readonly step: Cadence;
    /** Refs of the accounts checked under Cash accounts, whatever their currency. */
    readonly cash: ReadonlySet<string>;
    /** Refs of the accounts checked under Invested accounts, whatever their currency. */
    readonly invested: ReadonlySet<string>;
}

/** Empty dates, a monthly step - the cadence a budget already reads in - and no account checked. */
export const INITIAL_SETTINGS_FORM: ProjectionSettingsForm = {
    from: '',
    to: '',
    step: 'MONTHLY',
    cash: new Set(),
    invested: new Set(),
};

/**
 * `form` as the settings the ledger is asked for.
 *
 * Only accounts in `currency` are sent, in the order `options` offers them. A checked account
 * drops out of view when the scenario changes to another currency; filtering it out here, rather
 * than clearing the selection, means switching back restores it, while an account in another
 * currency is never sent - a projection covers one currency (ADR-012, ADR-016).
 *
 * @param form - the shared fields' current state.
 * @param options - every account on offer, in the order offered.
 * @param currency - the currency being projected; `undefined` sends no account at all.
 * @returns the horizon and step as entered, and the checked accounts in `currency`.
 */
export function toProjectionSettings(
    form: ProjectionSettingsForm,
    options: readonly AccountOption[],
    currency: string | undefined,
): ProjectionSettings {
    const checkedInCurrency = (checked: ReadonlySet<string>) =>
        options
            .filter((option) => option.currency === currency && checked.has(option.ref))
            .map((option) => option.ref);
    return {
        from: form.from,
        to: form.to,
        step: form.step,
        cash: checkedInCurrency(form.cash),
        invested: checkedInCurrency(form.invested),
    };
}
