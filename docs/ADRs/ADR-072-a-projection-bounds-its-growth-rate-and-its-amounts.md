# ADR-072: A projection bounds its growth rate and its amounts

- **Status:** Accepted
- **Date:** 2026-11-02

Extends [ADR-058](ADR-058-each-service-bounds-what-one-request-can-cost.md), which bounds how many
steps a projection takes, at most 100 years of them, and how many projections a comparison holds,
at most ten, but not what each step computes. ADR-058 names this case as one of its own revisit
triggers: "a decimal member needs a tighter bound than the body cap gives".

## Context

**A projection holds every point at once.** `Projection.of` compounds the growth rate into the
invested balance at every step and adds each step's flows to the cash, then the web adapter
renders every point with `toPlainString`. A weekly step over 100 years is about 5,200 points.

**What it computes had no bound beyond the 64 KiB body cap.**

- `GrowthAssumption` refused only a rate at or below -1. A rate of 5, typed to mean 5%, compounds
  to 6^5,200 over a century of weeks.
- A rate's decimal places were unbounded too. Each step multiplies the invested balance by the
  rate at its full precision before rounding to minor units, so a rate of thousands of digits
  costs that much on every step.
- An opening balance or a planned flow's amount can be tens of thousands of digits long, and a
  projection carries it into every point.

Scan 9 ([#346](https://github.com/NaimElijah/FinAssist/issues/346)) measured one projection of
such a scenario at seconds to tens of seconds of CPU and tens to hundreds of millions of characters
of response, and a comparison of ten multiplies that. Any principal with `ledger:read` can read a
projection once such a scenario exists; only the owner can define one.

**Three bounds were weighed** ([#416](https://github.com/NaimElijah/FinAssist/issues/416)): one on
the rate, one on a projection's magnitude, and one on the digits of every decimal on the wire. The
last couples all three contracts and the generated web types, and is a decision of its own.

## Decision

**A growth rate is bounded where it is built, and a projection refuses an amount past 18 integer
digits.** Both refusals are `IllegalArgumentException`, answered 422 `RULE_VIOLATED` by the
ledger's fallback handler (ADR-028), and neither message names the value refused, since a value
can be as long as the request that carried it. **The figures are the owner's decision, made on
2026-11-02.**

- **A rate is greater than -1, as before, and at most 1 a step.** `GrowthAssumption.MAXIMUM_RATE`.
  1 doubles the invested balance in one step, which no plan needs to exceed, and a rate above it
  is most often a percentage typed as a whole number.
- **A rate has at most ten decimal places** once its trailing zeros are dropped, so `0.10000000000`
  is the `0.1` it computes as. `GrowthAssumption.MAXIMUM_DECIMAL_PLACES`. Ten places are far finer
  than any assumed market rate is known.
- **The store holds the same two bounds.** `V3_3__scenario_growth_bounded.sql` adds
  `scenario_growth_at_most_one` (`growth_per_step <= 1`) and `scenario_growth_at_most_ten_places`
  (`min_scale(growth_per_step) <= 10`, the scale after trailing zeros, as Java counts it). Both are
  validated as they are added: a stored rate beyond either stops the upgrade, naming the
  constraint, rather than turning every later read of the scenario list into a 500. The first also
  refuses `NaN`, which `V3_1`'s `growth_per_step > -1` admits.
- **A projection refuses any amount of more than 18 integer digits,** that is, any amount at or
  beyond 10^18 either side of zero. `Projection.MAXIMUM_INTEGER_DIGITS`. `Projection.of` checks
  its inputs first, the opening cash, the opening investments and each planned flow's amount,
  before it expands a single recurrence, then every point's cash, invested balance and total as it
  builds them. The work stops at the first amount past the bound, so its cost is bounded whatever
  the inputs.
- **A comparison inherits the bound and adds no other.** A divergence is the difference of two
  points that each hold the bound, so it can reach 19 integer digits by construction, and it is
  reported as computed.
- **The contract's schemas do not change.** `DefineScenarioRequest.growthRatePerStep` gains a
  description stating the rate's bounds; the problem code stays `RULE_VIOLATED`.

## Consequences

**Makes easy**

- **One projection's cost has a ceiling.** At most about 5,200 points of at most 18 integer
  digits each, computed from a rate of at most ten decimal places.
- **The commonest typo is refused when the strategy is defined,** with a message that says how to
  write 5%, instead of surfacing as an unreadable projection.

**Makes hard**

- **An existing database can refuse to upgrade.** A rate above 1, or past ten places, stored
  before this record stops `V3_3`, and the owner corrects the row first. `docs/operations/operations.md`,
  "Upgrading an existing ledger volume", gives the query and the correction.
- **A legitimate figure past 10^18 cannot be projected.** No household holds one, and the owner
  decides the figure again if one ever does.
- **A divergence can hold one digit more than the amounts it compares,** so a wire-wide digit bound
  has to say what it allows on derived figures.
- **The rate's upper bound is written twice.** `GrowthAssumption.MAXIMUM_RATE` is a `BigDecimal`,
  which no annotation can read, so `DefineScenarioRequest.GROWTH_RATE` states it in words; the
  ledger's `CLAUDE.md` says to keep the two in step.

## Alternatives considered

- **Bound the rate alone.** Rejected: a rate of 1 still doubles a weekly balance 5,200 times, and an
  opening balance or a flow's amount is not a rate at all.
- **Bound every decimal's digits on the wire, in all three contracts, and in both `Money` kernels.**
  Not here: it changes every contract and the generated web types, and the two kernels' own gaps
  past `BigInteger`'s range are a separate matter of agreement between Java and Python (ADR-018).
  Filed as its own decision, [#434](https://github.com/NaimElijah/FinAssist/issues/434). **Revisit
  when** #434 is decided.
- **Answer an oversized projection with a code of its own.** Rejected for now: the horizon's cap is
  already `RULE_VIOLATED`, and the codes a contract names are
  [#406](https://github.com/NaimElijah/FinAssist/issues/406)'s decision. **Revisit when** #406
  names the problem codes.
- **Leave the stored constraints `NOT VALID`.** Rejected: a stored rate beyond the bounds would then
  be refused only on its way back into `GrowthAssumption`, as a 500 on every read of the scenario
  list, which is worse than a refused upgrade with a named constraint.

## Revisit when

- **The owner needs a rate above 100% a step, a finer rate than ten decimal places, or a figure
  past 10^18.** The owner decides the figure again.
- **#434 bounds every decimal on the wire.** This record's bound on a projection's amounts may then
  follow from the wire's, and the two must agree on derived figures.

## What lands with this record

- **The rate.** `GrowthAssumption`; `GrowthAssumptionTest`;
  `ScenarioControllerTest.Given_a_growth_rate_beyond_its_upper_bounds_When_defined_Then_it_is_a_422_rule_violated_problem`;
  `PlanningHttpIT.Given_growth_rates_beyond_the_upper_bounds_When_defined_Then_each_is_a_422_and_none_is_listed`;
  `DefineScenarioRequest.GROWTH_RATE` in `contracts/ledger-v1.openapi.json`.
- **The store.** `V3_3__scenario_growth_bounded.sql`; `JdbcPlanningRepositoriesIT`,
  `LedgerUpgradeIT` and `ReleasedMigrationsTest`.
- **The magnitude.** `Projection.MAXIMUM_INTEGER_DIGITS`; `ProjectionTest`, `ComparisonTest` and
  `PlanningHttpIT`.
- **Documentation.** ADR-058 reads `Accepted, extended by 072`, with its row in
  `docs/ADRs/README.md`, a row for 072 and a note; `docs/requirements/requirements.md`,
  `docs/architecture/architecture.md`, `docs/operations/operations.md` and the ledger's
  `CLAUDE.md`. The `Money` kernels' size notes in `MoneyTest`, `tests/test_money.py` and
  `shared/money.py` now point at #434.
