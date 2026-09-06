-- The planning context: what the owner intends, which is not what happened.
--
-- Ordinary mutable tables, for the reason ADR-015 separates the contexts in the first place:
-- planning holds intent and accounting holds fact, and an audit trail is a record of fact.
-- A plan that is abandoned still is not deleted - it becomes Cancelled carrying its reason, and
-- PlannedFlowRepository exposes no delete for it.
--
-- No foreign key here points outside the planning schema. An opening balance reaches this
-- context through OpeningPositionProvider and a translation in the composition root, addressed
-- by planning's own AccountRef, never by a join to accounting.account (ADR-022).

CREATE SCHEMA IF NOT EXISTS planning;

CREATE TABLE planning.planned_flow (
    id            uuid PRIMARY KEY,
    description   text    NOT NULL,
    -- Always positive: direction carries the sign here, the opposite of Entry, which is signed
    -- because double entry has to balance.
    amount        numeric NOT NULL,
    currency      text    NOT NULL,
    direction     text    NOT NULL,
    starts_on     date    NOT NULL,

    -- The sealed Recurrence, as a discriminator plus the columns each variant needs. The CHECK
    -- below is what makes the combinations the Java type cannot express unstorable too: Once
    -- carries no cadence, Until carries an end date and no count, Times carries a count and no
    -- end date.
    recurrence    text    NOT NULL,
    cadence       text,
    step_interval integer,
    recurs_until  date,
    occurrences   integer,

    -- The sealed PlanState, the same way. Planned carries no date; only Cancelled carries a
    -- reason, and its reason is never blank.
    state         text    NOT NULL,
    state_on      date,
    state_reason  text,

    CONSTRAINT planned_flow_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT planned_flow_amount_positive CHECK (amount > 0),
    CONSTRAINT planned_flow_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY')),
    CONSTRAINT planned_flow_direction_known CHECK (direction IN ('INFLOW', 'OUTFLOW', 'INVESTMENT')),
    CONSTRAINT planned_flow_cadence_known CHECK (cadence IS NULL OR cadence IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT planned_flow_recurrence_shape CHECK (
        (recurrence = 'ONCE' AND cadence IS NULL AND step_interval IS NULL
            AND recurs_until IS NULL AND occurrences IS NULL)
            OR (recurrence = 'UNTIL' AND cadence IS NOT NULL AND step_interval IS NOT NULL
                AND recurs_until IS NOT NULL AND occurrences IS NULL)
            OR (recurrence = 'TIMES' AND cadence IS NOT NULL AND step_interval IS NOT NULL
                AND recurs_until IS NULL AND occurrences IS NOT NULL)
    ),
    CONSTRAINT planned_flow_interval_positive CHECK (step_interval IS NULL OR step_interval >= 1),
    CONSTRAINT planned_flow_occurrences_positive CHECK (occurrences IS NULL OR occurrences >= 1),
    -- A recurrence that would end before it begins is refused, not answered with nothing.
    CONSTRAINT planned_flow_ends_after_it_starts CHECK (recurs_until IS NULL OR recurs_until > starts_on),
    CONSTRAINT planned_flow_state_shape CHECK (
        (state = 'PLANNED' AND state_on IS NULL AND state_reason IS NULL)
            OR (state = 'COMMITTED' AND state_on IS NOT NULL AND state_reason IS NULL)
            OR (state = 'REALISED' AND state_on IS NOT NULL AND state_reason IS NULL)
            OR (state = 'CANCELLED' AND state_on IS NOT NULL AND state_reason IS NOT NULL
                AND btrim(state_reason) <> '')
    )
);

CREATE TABLE planning.scenario (
    id              uuid PRIMARY KEY,
    name            text    NOT NULL,
    currency        text    NOT NULL,
    -- GrowthAssumption refuses a rate at or below -1: losing more than everything is not a rate.
    growth_per_step numeric NOT NULL,
    CONSTRAINT scenario_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT scenario_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY')),
    CONSTRAINT scenario_growth_above_minus_one CHECK (growth_per_step > -1)
);

-- The relation is stored by PlanId, never by value. A Scenario holds whole PlannedFlow values in
-- memory, but ForecastService deliberately re-reads each one from PlannedFlowRepository before
-- projecting, because the embedded copy goes stale the moment a plan is committed or cancelled.
-- Storing the copy would make the stale value durable.
CREATE TABLE planning.scenario_flow (
    scenario_id uuid    NOT NULL REFERENCES planning.scenario (id),
    ordinal     integer NOT NULL,
    flow_id     uuid    NOT NULL REFERENCES planning.planned_flow (id),
    PRIMARY KEY (scenario_id, ordinal),
    -- Scenario refuses the same planned flow listed twice; so does this.
    CONSTRAINT scenario_flow_listed_once UNIQUE (scenario_id, flow_id),
    CONSTRAINT scenario_flow_ordinal_not_negative CHECK (ordinal >= 0)
);
