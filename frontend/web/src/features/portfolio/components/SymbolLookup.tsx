import { type SubmitEvent, useId, useState } from 'react';
import { useNavigate } from 'react-router';

import { Button, TextField } from '@/shared/ui';

import { useRefreshHolding } from '../hooks/portfolioQueries';
import { holdingPath } from './paths';
import styles from './SymbolLookup.module.css';

/**
 * Matches a value with at least one character that is not white space, and that is not `.` or
 * `..` once trimmed. `required` alone accepts a value of only spaces, which trims to nothing and
 * would lead to no holding at all; and `generatePath` leaves a dot segment as it is, which the
 * router resolves to another page - `..` to the app's root. No ticker is either.
 */
const SYMBOL_PATTERN = '(?!\\s*\\.{1,2}\\s*$).*\\S.*';

/**
 * What the Symbol field takes and refuses. The pattern asks only for something not blank and not
 * a dot segment; the assistant upper-cases a symbol, so `vwce` and `VWCE` name one holding and the
 * case typed does not matter. The browser's own message for a pattern mismatch does not say which
 * rule failed, so the dot segment is named here - in words, since a screen reader skips
 * punctuation at its default verbosity and would read "a lone . or .." as "a lone or".
 */
const SYMBOL_HINT =
    'A ticker, in any letter case, for example VWCE; a lone dot or two dots is not a ticker';

/** What the lookup's section is called. */
export interface SymbolLookupProps {
    /** The section's heading, such as "Look up a holding". */
    readonly heading: string;
}

/**
 * A headed form that opens one holding's page by its symbol.
 *
 * The symbol is trimmed and otherwise kept as typed: upper-casing it is the assistant's job, and
 * the URL shows what the owner typed. Every lookup also reads the holding afresh
 * (`useRefreshHolding`), so looking up the symbol already on screen shows its latest figures
 * rather than the cached ones.
 *
 * @param props - see `SymbolLookupProps`.
 */
export function SymbolLookup({ heading }: SymbolLookupProps) {
    const headingId = useId();
    const [symbol, setSymbol] = useState('');
    const navigate = useNavigate();
    const refreshHolding = useRefreshHolding();

    function lookUp(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        // Native `required` and `pattern` refuse a blank or dot symbol before a submit the browser
        // validated; one dispatched by script arrives unvalidated, so the same rule is checked
        // here, and its native message shown, before anything navigates.
        if (!event.currentTarget.reportValidity()) {
            return;
        }
        const trimmed = symbol.trim();
        refreshHolding(trimmed);
        void navigate(holdingPath(trimmed));
    }

    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                {heading}
            </h2>
            <form className={styles.form} onSubmit={lookUp}>
                <TextField
                    label="Symbol"
                    name="symbol"
                    hint={SYMBOL_HINT}
                    required
                    pattern={SYMBOL_PATTERN}
                    autoComplete="off"
                    spellCheck={false}
                    value={symbol}
                    onChange={(event) => {
                        setSymbol(event.target.value);
                    }}
                />
                <div>
                    <Button type="submit">Look up</Button>
                </div>
            </form>
        </section>
    );
}
