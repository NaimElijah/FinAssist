# ADR-051: What a translation may decide

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-022](ADR-022-ledger-composition-root.md), whose last-but-one revisit trigger is
"`composition` acquires a decision rather than a mapping — a rule about which accounts are cash,
say". Two translations hold something that looks like a decision. This record says where the line
runs, and moves one rule across it.

## Context

**ADR-022 gave cross-context translations one home, `composition`, and one way to go wrong.** The
package may import every context. It can therefore hold a rule that belongs in one of them, and
nothing in the build says so ([#339](https://github.com/NaimElijah/FinAssist/issues/339)). Its
trigger describes the failure: "an invariant is homeless and belongs in a context, and the wiring
package has started to hold the domain the rules were meant to protect."

**Two translations in
`backend/ledger/src/main/java/io/github/naimelijah/finassist/ledger/composition/` held a rule, at
`1d4f7ba`.**

- **`AccountingSpendJournal`** keeps only `EXPENSE` lines as budgeting's spend, and only lines in
  the currency asked for. Its Javadoc gives the reason: an entry against the cash account is the
  other side of the same purchase, and counting both would double every figure.
- **`AccountingOpeningPositions`** refused an account reference named twice, or named on both the
  cash and the invested side. Its comment gave the reason: quietly collapsing it would hide the
  slip that doubles an opening balance.

**The two rules are different in kind.**

- **"An `EXPENSE` line is spend"** cannot be written without `AccountType`, which is accounting's
  vocabulary. Budgeting must never learn it. The rule answers a question the downstream asks, "what
  did I spend?", by choosing which upstream records answer it.
- **"Counting an account twice doubles an opening balance"** names only planning's own
  `AccountRef`s. It says nothing accounting would have to explain. It is a rule about the shape of
  planning's own input, as the refusal of a blank `AccountRef` already is
  (`AccountRef`'s constructor).

**Both rules were pinned, in the translation's own tests.**
`AccountingSpendJournalTest`
(`Given_a_purchase_When_spend_is_asked_for_Then_the_cash_side_is_not_counted` and
`Given_spend_in_another_currency_When_asked_for_shekels_Then_it_is_excluded`) and
`AccountingOpeningPositionsTest`
(`Given_an_account_named_twice_When_a_position_is_asked_for_Then_it_is_refused` and
`Given_an_account_named_on_both_sides_When_a_position_is_asked_for_Then_it_is_refused`, which
moved with the rule).

**The duplicate-account rule has two entrances.** `ProjectScenarioCommand` and
`CompareScenariosCommand` both carry `cashAccounts` and `investedAccounts`, and both reach
`positionOf` through `ForecastService`. A rule moved into one would leave the other without it.

## Decision

**Choosing which upstream records answer the downstream's question is translation, and stays in
`composition`.** "An `EXPENSE` line is spend" is the example. It needs accounting's vocabulary to
state, so no context may hold it. The currency filter belongs with it, since it implements the
`SpendJournal` query's own `currency` argument. So do the refusals that need an accounting value:
a malformed identifier, an unknown account, and a balance held in another currency.

**A rule expressible wholly in the downstream's own vocabulary is that context's invariant, and
moves into it.** "Counting an account twice doubles an opening balance" speaks only of planning's
`AccountRef`s, so planning refuses it.

- **Where.** In the constructors of `ProjectScenarioCommand` and `CompareScenariosCommand`
  (`planning/application/port/in/`), through one check they share:
  `AccountSelection.requireEachNamedOnce`, package-private beside them.
- **What the caller sees.** The answer is still 422 `RULE_VIOLATED`, with the same message,
  "Account reference … is named more than once; it would be counted twice". An
  `IllegalArgumentException` that no planning handler claims falls to `LedgerProblemHandler`.
- **What `AccountingOpeningPositions` keeps.** The translation drops the duplicate check and keeps
  the rest.

**Each translation states its rules in its Javadoc and pins them with a test of its own.**
`AccountingSpendJournal` already does. `AccountingOpeningPositions`' Javadoc is extended to state
the rules it keeps.

**The test for where a rule belongs** is one question: can it be stated without naming an upstream
type? If it can, it is the downstream context's, and the context refuses it. If it cannot, it is
translation.

## Consequences

**Makes easy**

- **Planning's input is valid wherever planning is used.** The rule holds for a caller other than
  the one translation, and survives a context being extracted (ADR-022's own aim).
- **The translation shrinks to a mapping.** `AccountingOpeningPositions` keeps only what needs an
  accounting value.
- **The next rule has a test to apply.** The question above settles it without an argument.

**Makes hard**

- **The refusal moves earlier, and three answers change.** The commands are built in the
  controller, before the service looks anything up (`ComparisonController` builds
  `CompareScenariosCommand`, ahead of `ForecastService.compare`). A client sees:
  - **Projection, unknown scenario plus a duplicate account:** 422 `RULE_VIOLATED`. It was 404
    `SCENARIO_NOT_FOUND`, because `ForecastService.project` looks the scenario up first.
    `PlanningHttpIT.Given_an_unknown_scenario_and_an_account_named_twice_When_projected_Then_it_is_a_422_rule_violated_problem`
    pins it.
  - **Comparison, unknown first scenario plus a duplicate account:** 422 `RULE_VIOLATED`. It was
    422 `UNKNOWN_SCENARIO`. The web client branches on `UNKNOWN_SCENARIO` (`planningApi.ts`), so for
    that one request it now shows the duplicate-account detail rather than the unknown-scenario
    message.
  - **Comparison, a plan missing from the store plus a duplicate account:** 422. It was 500, since
    a missing plan means a corrupt store.

  For `compare`, the check also ran only when the first scenario needed an opening position, and
  now runs always. A request with no duplicate is answered as before.
- **Two commands share a check.** One helper in planning, called by both constructors, is another
  thing to keep in step.
- **The line is a judgement.** The question is crisp for these two rules. A rule that needs both
  vocabularies, for example a currency check against an upstream balance, still has to be placed by
  hand. It stays in the translation.

**Accepted cost**

- **`composition` still holds real behaviour**, as ADR-022 accepted. This record narrows it and
  does not remove it.

## Alternatives considered

- **Leave both rules in the translation.** Rejected for the duplicate check: it is planning's
  invariant held in the one package that can see accounting, and a caller that builds a command
  without the translation never meets it. Kept for the `EXPENSE` filter, which cannot move.
- **Move both into their contexts.** Rejected: `EXPENSE` is `AccountType`, and moving it into
  budgeting would make budgeting import accounting, which `contexts_do_not_depend_on_each_other`
  forbids.
- **Check duplicates inside `ForecastService`.** Rejected: the rule is about the command's own
  fields, so it belongs in the command, as `AccountRef`'s blank check does. In the service it would
  be written once for `project` and once for `compare`.

## Revisit when

- **A rule appears that needs both vocabularies.** Two such rules mean the question above is too
  coarse, and the line needs a third category.
- **A third translation appears whose rules do not fit.** That is ADR-022's own trigger, and it
  stands.
- **A context is extracted into its own service.** The translation becomes an HTTP adapter, and
  the rules this record left in it go with it.

## What lands with this record

- **Status line and README.** ADR-022's Status line and README row name this record as an
  extension, and a note in `docs/ADRs/README.md` records the change.
- **A behaviour-preserving move in `backend/ledger`**, with the one ordering change above.
  - `AccountSelection.requireEachNamedOnce` holds the duplicate check, and both commands call it.
    The message is unchanged.
  - `ProjectScenarioCommandTest` and `CompareScenariosCommandTest` pin it for a cash account named
    twice, an invested one named twice, and one named on both sides.
  - `AccountingOpeningPositions`' Javadoc states the rules it keeps.
