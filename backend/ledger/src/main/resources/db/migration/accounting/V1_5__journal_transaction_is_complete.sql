-- A journal header is never committed alone (ADR-006, ADR-011, ADR-020).
--
-- V1_1's balance check fires on entries, so a header written with none was never checked at all,
-- and one written without its opening state has no current state for anything to read: the
-- ledger's own query joins on the latest state row, so such a header and its entries vanish from
-- every list and every balance while the database still holds them. This deferred check refuses,
-- at commit, a header that has no entry or no state at sequence 0.
--
-- It asks for one entry, not two, on purpose. V1_1's refuse_unbalanced_transaction() already
-- refuses fewer than two once a transaction has any; this check closes only the case that one
-- never sees. Asking for two here would hold one rule in two places under two messages, and which
-- of them a one-entry transaction met would depend on the order deferred triggers fire in.
--
-- DEFERRABLE INITIALLY DEFERRED because the header is written before its entries and its state,
-- as JdbcTransactionRepository writes them; checked per statement, every header would fail.
--
-- CREATE TRIGGER is DDL and touches no row, so this migration cannot fail on rows already stored.

CREATE FUNCTION accounting.refuse_incomplete_transaction() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM accounting.journal_entry WHERE transaction_id = NEW.id) THEN
        RAISE EXCEPTION 'transaction % has no entries; a transaction is recorded with them (ADR-011)',
            NEW.id;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM accounting.journal_transaction_state
                   WHERE transaction_id = NEW.id
                     AND sequence = 0) THEN
        RAISE EXCEPTION 'transaction % has no opening state; a transaction is recorded with it (ADR-020)',
            NEW.id;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_transaction_is_complete
    AFTER INSERT
    ON accounting.journal_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION accounting.refuse_incomplete_transaction();
