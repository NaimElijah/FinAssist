-- Records who wrote each journal row, from this migration onward (ADR-033).
--
-- Every row already in this table was written before the ledger had an authenticated caller.
-- Backfilling a recorder onto one would invent a fact nobody has: it would honestly name nobody,
-- and this migration leaves it saying exactly that.
--
-- `recorded_by text`, nullable, with no default. A default would stamp a false recorder onto
-- every historical row the moment this column appeared, which is worse than the row saying
-- nothing. Its shape mirrors accounting.application.port.in.Actor: a non-blank subject of at
-- most 255 characters (Actor.MAX_SUBJECT_LENGTH) — the two are kept in sync by review, since no
-- constant can be shared across a Java class and a SQL constraint.
--
-- The CHECK is added NOT VALID on purpose. NOT VALID tells PostgreSQL to skip scanning the rows
-- already in the table, so the historical NULLs above are simply never checked against it, while
-- every row written from this moment on — through this connection or any other — is still checked
-- as it is written. NOT VALID never weakens enforcement of a new row; it only excuses the
-- constraint from re-checking history it cannot rewrite anyway: accounting.refuse_rewrite()
-- forbids an UPDATE on this table, so a backfill could not run even if one were wanted, which is
-- why NOT VALID is the only legal option here, not merely the convenient one.
--
-- NEVER VALIDATE THESE CONSTRAINTS. `ALTER TABLE ... VALIDATE CONSTRAINT` scans every existing
-- row and would fail on the very NULLs this migration deliberately leaves in place, turning an
-- honest "nobody recorded this" into a startup failure over rows nobody can ever correct — the
-- same append-only triggers that protect the journal also block the UPDATE a validation failure
-- would demand. JournalActorMigrationIT proves this directly: adding VALIDATE CONSTRAINT to that
-- test's own migration path makes it fail against the historical rows it seeds, which is exactly
-- why this migration never runs one.
--
-- ADD COLUMN is DDL and fires none of accounting.refuse_rewrite() or
-- accounting.refuse_unbalanced_transaction() — both are row triggers, and DDL touches no row.
-- JournalActorMigrationIT confirms this against the real schema rather than assuming it: it runs
-- this migration against a database already holding a transaction and its state rows and asserts
-- the migration succeeds and every historical row survives untouched.

ALTER TABLE accounting.journal_transaction
    ADD COLUMN recorded_by text;

ALTER TABLE accounting.journal_transaction
    ADD CONSTRAINT journal_transaction_recorded_by_shape
        CHECK (recorded_by IS NOT NULL AND btrim(recorded_by) <> '' AND char_length(recorded_by) <= 255)
        NOT VALID;

ALTER TABLE accounting.journal_transaction_state
    ADD COLUMN recorded_by text;

ALTER TABLE accounting.journal_transaction_state
    ADD CONSTRAINT journal_transaction_state_recorded_by_shape
        CHECK (recorded_by IS NOT NULL AND btrim(recorded_by) <> '' AND char_length(recorded_by) <= 255)
        NOT VALID;
