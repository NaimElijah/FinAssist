-- A transaction's state history admits only the lifecycle the domain allows (ADR-006, ADR-020).
--
-- A lifecycle change is a new row in journal_transaction_state and the current state is the one
-- with the highest sequence, so a row the domain could never have produced rewrites the record by
-- addition: a PENDING row after a RECONCILED one un-reconciles a frozen transaction without
-- touching a row the append-only triggers guard. This trigger checks each new row against the one
-- before it, mirroring Transaction.clear, reconcile and markVoided:
--
--   * the history opens PENDING at sequence 0;
--   * each later row takes the next sequence, so the current state is never reached by a jump;
--   * PENDING moves to CLEARED or VOIDED, CLEARED to RECONCILED or VOIDED, and RECONCILED and
--     VOIDED are terminal;
--   * a state is never dated before the transaction occurred or before the state it follows.
--
-- Every refusal names the transaction and the states, never a date: a database error's message is
-- logged with every 5xx it causes, and financial data is redacted at the logging boundary rather
-- than handed to it by the database.
--
-- Two callers transitioning the same transaction at once still collide on the primary key
-- (transaction_id, sequence), as V1_1 intends, and the loser fails rather than appending a state
-- after one it never read; a caller that instead skipped or reused a sequence is refused here.
--
-- CREATE TRIGGER is DDL and touches no row, so this migration cannot fail on rows already stored,
-- and a history written before it is not re-checked.

CREATE FUNCTION accounting.refuse_illegal_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    previous accounting.journal_transaction_state%ROWTYPE;
    occurred date;
BEGIN
    SELECT *
    INTO previous
    FROM accounting.journal_transaction_state
    WHERE transaction_id = NEW.transaction_id
    ORDER BY sequence DESC
    LIMIT 1;

    IF NOT FOUND THEN
        IF NEW.sequence <> 0 OR NEW.state <> 'PENDING' THEN
            RAISE EXCEPTION 'transaction % opens PENDING at sequence 0, not % at %',
                NEW.transaction_id, NEW.state, NEW.sequence;
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.sequence <> previous.sequence + 1 THEN
        RAISE EXCEPTION 'transaction %: the next state is sequence %, not %',
            NEW.transaction_id, previous.sequence + 1, NEW.sequence;
    END IF;

    IF NOT ((previous.state = 'PENDING' AND NEW.state IN ('CLEARED', 'VOIDED'))
        OR (previous.state = 'CLEARED' AND NEW.state IN ('RECONCILED', 'VOIDED'))) THEN
        RAISE EXCEPTION 'transaction % cannot move from % to %',
            NEW.transaction_id, previous.state, NEW.state;
    END IF;

    SELECT occurred_on
    INTO occurred
    FROM accounting.journal_transaction
    WHERE id = NEW.transaction_id;

    -- previous.effective_on is NULL for PENDING, and a comparison with NULL is never true, so only
    -- the occurrence date bounds a move out of PENDING.
    IF NEW.effective_on < occurred OR NEW.effective_on < previous.effective_on THEN
        RAISE EXCEPTION 'transaction % cannot be % before it occurred or before its previous state',
            NEW.transaction_id, NEW.state;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_transaction_state_moves_forward
    BEFORE INSERT
    ON accounting.journal_transaction_state
    FOR EACH ROW
EXECUTE FUNCTION accounting.refuse_illegal_transition();
