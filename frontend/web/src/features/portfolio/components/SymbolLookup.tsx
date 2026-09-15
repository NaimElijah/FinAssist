import { type SubmitEvent, useId, useState } from 'react';
import { useNavigate } from 'react-router';

import { Button, TextField } from '@/shared/ui';

import { useRefreshHolding } from '../hooks/portfolioQueries';
import { holdingPath } from './paths';
import styles from './SymbolLookup.module.css';

/**
 * Matches a value with at least one character that is not white space. `required` alone accepts a
 * value of only spaces, which trims to nothing and would lead to no holding at all.
 */
const NOT_BLANK_PATTERN = '.*\\S.*';

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
        // Native `required` and `pattern` refuse a blank symbol before this runs.
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
                    required
                    pattern={NOT_BLANK_PATTERN}
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
