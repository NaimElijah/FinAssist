-- An entry stays in its account's currency, and an account's currency is fixed once it has one
-- (ADR-012).
--
-- V1_1's refuse_unbalanced_transaction() compared each entry with its account when the entry was
-- written, but nothing held the account to it afterwards: JdbcAccountRepository's upsert rewrites
-- an account's currency on a re-save, which left entries in a currency their account no longer
-- held, and every balance read of that account then failed. A composite foreign key states both
-- halves as one rule: a new entry must name its account's (id, currency), and an account's currency
-- cannot change while an entry names the old pair.
--
-- A foreign key needs a unique index on the columns it references. id alone is already the primary
-- key, so (id, currency) refuses nothing new; the index exists only to be referenced. The chart of
-- accounts is a handful of rows, so a plain CREATE UNIQUE INDEX, which locks writes for the moment
-- it takes to build, is enough.
--
-- The key is added NOT VALID, and must never be validated. NOT VALID skips only the scan of the
-- entries already stored, so a database that already holds such a mismatch still migrates; every
-- entry written from now on is checked, and a change to a referenced account's currency is refused
-- whatever the key's validation status, because those checks are triggers PostgreSQL creates with
-- the key itself. VALIDATE CONSTRAINT would scan the history and fail on any mismatch it found, and
-- the append-only triggers forbid the UPDATE of an entry that a correction would need, as V1_2 says
-- of its own NOT VALID checks (ADR-033). LedgerUpgradeIT starts the ledger against such a row.

CREATE UNIQUE INDEX account_id_currency_key ON accounting.account (id, currency);

ALTER TABLE accounting.journal_entry
    ADD CONSTRAINT journal_entry_in_account_currency
        FOREIGN KEY (account_id, currency) REFERENCES accounting.account (id, currency)
        NOT VALID;
