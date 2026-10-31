-- The deferred balance check holds a transaction's entries to its header's currency, and names no
-- amount when it refuses (ADR-011, ADR-012).
--
-- Replaces V1_1's accounting.refuse_unbalanced_transaction(); the constraint trigger
-- journal_transaction_balances keeps calling it by name, so nothing else changes. Three changes:
--
--   * journal_transaction.currency was never compared with the entries, so a header could state one
--     currency over entries in another. The entries' single currency must now be the header's.
--   * The unbalanced refusal no longer prints the sum. A database error's message is logged with
--     every 5xx it causes, and financial data is redacted at the logging boundary, never handed to
--     it by the database: the message names the transaction and the rule, which is enough to find
--     the rows.
--   * The entry-matches-its-account branch is gone. V1_6's foreign key journal_entry_in_account_currency
--     refuses such an entry as it is written, before this deferred check runs, so the branch could
--     no longer be reached by any row.
--
-- CREATE OR REPLACE FUNCTION touches no row, so this migration cannot fail on rows already stored.

CREATE OR REPLACE FUNCTION accounting.refuse_unbalanced_transaction() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    entries         integer;
    currencies      integer;
    entry_currency  text;
    total           numeric;
    header_currency text;
BEGIN
    SELECT count(*), count(DISTINCT currency), min(currency), coalesce(sum(amount), 0)
    INTO entries, currencies, entry_currency, total
    FROM accounting.journal_entry
    WHERE transaction_id = NEW.transaction_id;

    -- A transaction whose rows are gone is not this trigger's business; nothing deletes them
    -- anyway, and a deferred check can fire after the statement that would have.
    IF entries = 0 THEN
        RETURN NULL;
    END IF;

    IF entries < 2 THEN
        RAISE EXCEPTION 'transaction % has % entry; double entry needs at least two (ADR-011)',
            NEW.transaction_id, entries;
    END IF;

    IF currencies <> 1 THEN
        RAISE EXCEPTION 'transaction % mixes % currencies; amounts in two are never combined (ADR-012)',
            NEW.transaction_id, currencies;
    END IF;

    IF total <> 0 THEN
        RAISE EXCEPTION 'transaction % does not balance: its entries do not sum to zero (ADR-011)',
            NEW.transaction_id;
    END IF;

    SELECT currency
    INTO header_currency
    FROM accounting.journal_transaction
    WHERE id = NEW.transaction_id;

    IF entry_currency <> header_currency THEN
        RAISE EXCEPTION 'transaction % posts its entries in a currency other than its own (ADR-012)',
            NEW.transaction_id;
    END IF;

    RETURN NULL;
END;
$$;
