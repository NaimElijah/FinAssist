-- The budgeting context: limits the owner sets, and the rules that sort spend into categories.
--
-- Ordinary mutable tables, deliberately. ADR-020's append-only obligation binds *the journal* -
-- "no UPDATE or DELETE on a recorded transaction's entries" - and a budget is not a record of
-- what happened. Giving it append-only storage would be inventing a requirement nothing states.
-- The no-delete port contract still holds: BudgetRepository exposes save and findById only.
--
-- No foreign key here points outside the budgeting schema. Spend reaches this context through
-- SpendJournal and a translation in the composition root, never through a join (ADR-022).

CREATE SCHEMA IF NOT EXISTS budgeting;

CREATE TABLE budgeting.budget (
    id           uuid PRIMARY KEY,
    period_start date NOT NULL,
    period_end   date NOT NULL,
    currency     text NOT NULL,
    -- BudgetPeriod is half-open, and a period that ends before it starts is refused rather than
    -- answered with nothing.
    CONSTRAINT budget_period_ordered CHECK (period_start < period_end),
    CONSTRAINT budget_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY'))
);

CREATE TABLE budgeting.budget_limit (
    budget_id uuid    NOT NULL REFERENCES budgeting.budget (id),
    category  text    NOT NULL,
    amount    numeric NOT NULL,
    currency  text    NOT NULL,
    -- The primary key is what refuses a category listed twice in one budget, which Budget.of
    -- refuses with putIfAbsent.
    PRIMARY KEY (budget_id, category),
    CONSTRAINT budget_limit_positive CHECK (amount > 0),
    -- Category normalises to stripped lower case on construction; storing anything else would
    -- make two spellings of one category look like two categories.
    CONSTRAINT budget_limit_category_normalised CHECK (category = lower(btrim(category)) AND category <> ''),
    CONSTRAINT budget_limit_currency_known CHECK (currency IN ('ILS', 'USD', 'EUR', 'GBP', 'JPY'))
);

-- ordinal exists because CategorisationRuleRepository.all() promises insertion order, and its
-- Javadoc calls that part of the contract: returning rules in the order they were added is what
-- makes a budget review reproducible, where a set would make it depend on a hash.
CREATE TABLE budgeting.categorisation_rule (
    ordinal  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fragment text NOT NULL,
    category text NOT NULL,
    CONSTRAINT categorisation_rule_fragment_normalised CHECK (fragment = lower(btrim(fragment)) AND fragment <> ''),
    CONSTRAINT categorisation_rule_category_normalised CHECK (category = lower(btrim(category)) AND category <> '')
);
