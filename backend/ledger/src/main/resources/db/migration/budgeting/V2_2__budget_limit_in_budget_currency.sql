-- Every limit is in its budget's currency (requirements: "A budget has one limit per category, all
-- in the budget's currency"). Budget refuses the pair in Java; this makes the database refuse it too
-- (ADR-006), so no writer can store a limit that would leave its budget unreadable.
--
-- A composite foreign key is how a currency held in one table is tied to a currency held in
-- another: it needs (id, currency) to be unique on the referenced side, which it trivially is,
-- since id alone is the primary key.
--
-- The key is added and validated in one statement rather than NOT VALID and then VALIDATE. Flyway
-- runs this script in one transaction, so the lock the ADD takes is held until commit either way,
-- and a separate VALIDATE would not shorten it. Validation scans the existing limits: one in
-- another currency than its budget fails this migration, and so the ledger's start, where it would
-- otherwise fail every later read of that budget.

ALTER TABLE budgeting.budget
    ADD CONSTRAINT budget_id_currency_key UNIQUE (id, currency);

ALTER TABLE budgeting.budget_limit
    ADD CONSTRAINT budget_limit_in_budget_currency
        FOREIGN KEY (budget_id, currency) REFERENCES budgeting.budget (id, currency);
