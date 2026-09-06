-- The accounting context: the record of what happened, and the audit trail itself (ADR-020).
--
-- Each context owns a major version (accounting 1, budgeting 2, planning 3). Flyway scans
-- db/migration recursively and keeps one history table, so versions must be unique across all
-- three directories; the major is what guarantees that without anyone coordinating.
--
-- No foreign key in this file points outside the accounting schema, and none ever may. That is
-- the storage-layer half of the rule LedgerArchitectureTest enforces in Java, and
-- SchemaBoundariesIT fails the build if it is broken (ADR-024).

CREATE SCHEMA IF NOT EXISTS accounting;

-- Money is numeric with no declared precision throughout. PostgreSQL preserves the exact scale
-- it is given, which is what Money requires: its constructor calls setScale(minorUnits,
-- UNNECESSARY) and *rejects* excess precision rather than rounding it away. JPY is scale 0 and
-- the other four are scale 2, so numeric(19,2) would be a latent defect rather than a constraint.

CREATE TABLE accounting.account (
    id              uuid PRIMARY KEY,
    name            text NOT NULL,
    -- Written by the adapter as name.strip().toLowerCase(Locale.ROOT), not by a generated column:
    -- PostgreSQL's lower() follows the database collation and Java's does not, and this index has
    -- to agree with findByName exactly. It is what makes AccountRepository.save enforce
    -- uniqueness atomically, which its Javadoc has promised since ADR-021 - a check followed by a
    -- write is not a rule, because two callers can pass the check together.
    normalised_name text NOT NULL,
    type            text NOT NULL,
    currency        text NOT NULL,
    CONSTRAINT account_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT account_type_known CHECK (type IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE', 'EQUITY')),
    CONSTRAINT account_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY'))
);

CREATE UNIQUE INDEX account_normalised_name_key ON accounting.account (normalised_name);

CREATE TABLE accounting.journal_transaction (
    id          uuid PRIMARY KEY,
    occurred_on date NOT NULL,
    description text NOT NULL,
    currency    text NOT NULL,
    CONSTRAINT journal_transaction_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT journal_transaction_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY'))
);

-- ordinal preserves List<Entry> order, which is part of the aggregate rather than incidental.
CREATE TABLE accounting.journal_entry (
    transaction_id uuid    NOT NULL REFERENCES accounting.journal_transaction (id),
    ordinal        integer NOT NULL,
    account_id     uuid    NOT NULL REFERENCES accounting.account (id),
    amount         numeric NOT NULL,
    currency       text    NOT NULL,
    PRIMARY KEY (transaction_id, ordinal),
    -- Entry refuses a zero amount: an entry that moves nothing is a mistake, not a no-op.
    CONSTRAINT journal_entry_amount_not_zero CHECK (amount <> 0),
    CONSTRAINT journal_entry_ordinal_not_negative CHECK (ordinal >= 0),
    CONSTRAINT journal_entry_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY'))
);

-- findByAccount scans for the account, so it gets an index; findBetween uses occurred_on.
CREATE INDEX journal_entry_account_idx ON accounting.journal_entry (account_id);
CREATE INDEX journal_transaction_occurred_on_idx ON accounting.journal_transaction (occurred_on, id);

-- A lifecycle change is a new row, never an overwrite. Pending -> Cleared -> Reconciled is three
-- inserts, and the whole history stays readable. ADR-020 asks for exactly this, in these words:
-- "lifecycle changes recorded as new state rather than as an overwrite".
--
-- The primary key doubles as optimistic concurrency: two callers transitioning the same
-- transaction at once collide on (transaction_id, sequence) and one fails, rather than one
-- silently overwriting the other.
CREATE TABLE accounting.journal_transaction_state (
    transaction_id uuid    NOT NULL REFERENCES accounting.journal_transaction (id),
    sequence       integer NOT NULL,
    state          text    NOT NULL,
    effective_on   date,
    reason         text,
    PRIMARY KEY (transaction_id, sequence),
    CONSTRAINT transaction_state_sequence_not_negative CHECK (sequence >= 0),
    -- Mirrors the sealed TransactionState variant for variant, so a combination the Java type
    -- cannot express is one the database will not store either. Pending carries no date; only
    -- Voided carries a reason, and Voided's reason is never blank.
    CONSTRAINT transaction_state_shape CHECK (
        (state = 'PENDING' AND effective_on IS NULL AND reason IS NULL)
            OR (state = 'CLEARED' AND effective_on IS NOT NULL AND reason IS NULL)
            OR (state = 'RECONCILED' AND effective_on IS NOT NULL AND reason IS NULL)
            OR (state = 'VOIDED' AND effective_on IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> '')
    )
);

-- ---------------------------------------------------------------------------------------------
-- The journal is append-only, and this is where that stops being a convention (ADR-020).
--
-- A trigger rather than REVOKE UPDATE, DELETE: in Compose and in every developer database the
-- application role owns these tables, and an owner can grant the right back to itself, so a
-- revoke would look like enforcement and be none. A trigger binds every role, including the
-- owner and a human in psql. The revoke becomes the right complement the day a distinct,
-- non-owning application role exists.
--
-- TRUNCATE is deliberately not blocked: row triggers do not fire for it, it needs table
-- ownership that the eventual production role will not hold, and it is how the integration tests
-- isolate without rebuilding the schema per test. ADR-024 records that trade.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION accounting.refuse_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'the journal is append-only: % on %.% is refused (ADR-020)',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER journal_transaction_is_append_only
    BEFORE UPDATE OR DELETE
    ON accounting.journal_transaction
    FOR EACH ROW
EXECUTE FUNCTION accounting.refuse_rewrite();

CREATE TRIGGER journal_entry_is_append_only
    BEFORE UPDATE OR DELETE
    ON accounting.journal_entry
    FOR EACH ROW
EXECUTE FUNCTION accounting.refuse_rewrite();

CREATE TRIGGER journal_transaction_state_is_append_only
    BEFORE UPDATE OR DELETE
    ON accounting.journal_transaction_state
    FOR EACH ROW
EXECUTE FUNCTION accounting.refuse_rewrite();

-- ---------------------------------------------------------------------------------------------
-- The books balance, checked by the database as well as by the constructor.
--
-- ADR-006 asked for constraints that enforce invariants at the storage layer "so that a bug in
-- one is caught by the other". This is that pairing for the three rules a single row cannot
-- express: at least two entries, one currency, summing to zero (ADR-011, ADR-012), plus the
-- entry-matches-its-account rule that TransactionService holds because an Entry carries an
-- AccountId rather than an Account.
--
-- DEFERRABLE INITIALLY DEFERRED because the entries of one transaction arrive as separate
-- inserts: checked per statement, the first entry would always fail.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION accounting.refuse_unbalanced_transaction() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    entries    integer;
    currencies integer;
    total      numeric;
    mismatched integer;
BEGIN
    SELECT count(*), count(DISTINCT currency), coalesce(sum(amount), 0)
    INTO entries, currencies, total
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
        RAISE EXCEPTION 'transaction % does not balance: its entries sum to % (ADR-011)',
            NEW.transaction_id, total;
    END IF;

    SELECT count(*)
    INTO mismatched
    FROM accounting.journal_entry entry
             JOIN accounting.account account ON account.id = entry.account_id
    WHERE entry.transaction_id = NEW.transaction_id
      AND entry.currency <> account.currency;

    IF mismatched <> 0 THEN
        RAISE EXCEPTION 'transaction % posts % entries in a currency their account does not hold',
            NEW.transaction_id, mismatched;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_transaction_balances
    AFTER INSERT
    ON accounting.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION accounting.refuse_unbalanced_transaction();
