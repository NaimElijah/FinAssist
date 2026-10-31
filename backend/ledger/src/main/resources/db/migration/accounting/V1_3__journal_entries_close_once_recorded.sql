-- A recorded transaction's set of entries is closed (ADR-020, ADR-024).
--
-- V1_1's triggers refuse an UPDATE or a DELETE on an entry, but an INSERT is how every entry
-- arrives, so they let a later one through. A balanced pair added to a recorded transaction passes
-- the deferred balance check and still moves the balance of every account it touches, with no
-- state row recording that anything happened: a rewrite by addition. This trigger refuses an entry
-- for a transaction that already has a state row.
--
-- JdbcTransactionRepository writes the header, then every entry, then the opening state, in one
-- database transaction, so its own entries always arrive before the first state row and never trip
-- this. The state row is the marker because it is the adapter's last write: a header alone does not
-- yet mean the transaction is recorded.
--
-- CREATE TRIGGER is DDL and touches no row, so this migration cannot fail on rows already stored.

CREATE FUNCTION accounting.refuse_entry_on_recorded_transaction() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
               FROM accounting.journal_transaction_state
               WHERE transaction_id = NEW.transaction_id) THEN
        RAISE EXCEPTION 'the journal is append-only: transaction % is already recorded and its entries are closed (ADR-020)',
            NEW.transaction_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entry_closed_once_recorded
    BEFORE INSERT
    ON accounting.journal_entry
    FOR EACH ROW
EXECUTE FUNCTION accounting.refuse_entry_on_recorded_transaction();
