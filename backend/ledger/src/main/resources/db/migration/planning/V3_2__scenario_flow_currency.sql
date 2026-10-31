-- Every flow in a strategy is in the strategy's currency (requirements: "Every flow in a strategy
-- is in the strategy's currency"). Scenario refuses the pair in Java; this makes the database
-- refuse it too (ADR-006), so no writer can store a membership that would leave its strategy
-- unreadable.
--
-- The two currencies live in planning.scenario and planning.planned_flow, and the link between
-- them held neither, so nothing could compare them. The link now carries a currency, and two
-- composite foreign keys tie it to both ends: one currency equal to the scenario's and to the
-- plan's makes the two equal. Each key needs (id, currency) unique on its referenced side, which
-- it trivially is, since id alone is the primary key.
--
-- This stores a currency, not a copy of the plan. V3_1 stores the relation by plan id so that a
-- plan committed or cancelled after the strategy was named is read back as it now is; a plan's
-- currency is part of its amount, which no transition changes, and the key to planned_flow refuses
-- an edit that would change it while a strategy lists it, so this column cannot go stale.
--
-- The column is backfilled from the plan each existing link names before it is made NOT NULL.
-- Each key is validated as it is added: Flyway runs this script in one transaction, so the lock
-- the ADD takes is held until commit either way. A stored link whose plan is in another currency
-- than its strategy fails this migration, and so the ledger's start, where it would otherwise
-- fail every later read of that strategy.

ALTER TABLE planning.scenario
    ADD CONSTRAINT scenario_id_currency_key UNIQUE (id, currency);

ALTER TABLE planning.planned_flow
    ADD CONSTRAINT planned_flow_id_currency_key UNIQUE (id, currency);

ALTER TABLE planning.scenario_flow
    ADD COLUMN currency text;

UPDATE planning.scenario_flow link
   SET currency = flow.currency
  FROM planning.planned_flow flow
 WHERE flow.id = link.flow_id;

ALTER TABLE planning.scenario_flow
    ALTER COLUMN currency SET NOT NULL;

-- Implied by the keys below, and stated anyway: SchemaBoundariesIT holds every currency column to
-- the currency table through a CHECK of its own.
ALTER TABLE planning.scenario_flow
    ADD CONSTRAINT scenario_flow_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY'));

ALTER TABLE planning.scenario_flow
    ADD CONSTRAINT scenario_flow_in_scenario_currency
        FOREIGN KEY (scenario_id, currency) REFERENCES planning.scenario (id, currency);

ALTER TABLE planning.scenario_flow
    ADD CONSTRAINT scenario_flow_in_plan_currency
        FOREIGN KEY (flow_id, currency) REFERENCES planning.planned_flow (id, currency);
