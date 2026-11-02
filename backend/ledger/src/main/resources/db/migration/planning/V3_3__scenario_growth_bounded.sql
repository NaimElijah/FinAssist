-- A strategy's growth rate is at most 1 a step and has at most ten decimal places (ADR-072).
-- GrowthAssumption refuses any other in Java; this makes the database refuse it too (ADR-006), so no
-- writer can store a rate the aggregate would refuse to read back, which would answer 500 on every
-- read of the scenario list.
--
-- min_scale is the scale a value needs once its trailing zeros are dropped, which is how
-- GrowthAssumption counts decimal places: 0.10000000000 is 0.1 to both. The first check also
-- refuses NaN, which numeric admits and V3_1's growth_per_step > -1 lets through, since PostgreSQL
-- sorts NaN above every number; min_scale(NaN) is NULL, which a CHECK passes.
--
-- Each check is validated as it is added, so the rates already stored are scanned. Before this
-- version the ledger accepted any rate above -1, so a stored one beyond either bound fails this
-- migration, and so the ledger's start, naming the constraint and no value. The owner corrects the
-- row and starts the ledger again: docs/operations/operations.md, "Upgrading an existing ledger
-- volume". A check added NOT VALID would start the ledger over such a row unseen.

ALTER TABLE planning.scenario
    ADD CONSTRAINT scenario_growth_at_most_one CHECK (growth_per_step <= 1);

ALTER TABLE planning.scenario
    ADD CONSTRAINT scenario_growth_at_most_ten_places CHECK (min_scale(growth_per_step) <= 10);
