# ADR-022: A composition root package in the ledger, exempt from context isolation

- **Status:** Accepted, extended by [ADR-051](ADR-051-what-a-translation-may-decide.md)
- **Date:** 2026-09-01

## Context

[ADR-021](ADR-021-application-layer-shape.md) gives `budgeting` a `SpendJournal` port and
`planning` an `OpeningPositionProvider`, each speaking its own vocabulary. Something has to
implement them, and that something must read `accounting`. The question is where it may live.

`LedgerArchitectureTest.contexts_do_not_depend_on_each_other` matches
`io.github.naimelijah.finassist.ledger.(*)..` and admits one exemption, for `shared` as a
*target*. Every candidate location fails it:

- `budgeting/adapter/out/...` — the slice rule applies at every layer, adapters included, so a
  budgeting adapter importing `accounting` fails. The rule has no layer qualifier and should not
  grow one; "adapters may reach across" is precisely the hole the rule exists to close.
- `accounting/adapter/out/...` — the same failure in the other direction, and worse: it makes the
  upstream context aware of a downstream consumer.
- `shared` — exempt as a target, not as an origin. A class in `shared` importing `accounting` is
  still a `shared → accounting` slice dependency, and `shared` is the one package where coupling
  can accumulate unchallenged. Putting the wiring there would be the worst available answer.

There was one legal location already: the root package itself. `LedgerApplication` sits in
`io.github.naimelijah.finassist.ledger`, which the pattern `ROOT.(*)..` never captures, so a class
beside it belongs to no slice and the rule does not see it. That works and reads badly — three or
four wiring classes flat in the root package, next to the entry point, distinguished from nothing.

Meanwhile the assistant already solved this problem. `src/finassist_assistant/adapters/` is a
composition root that sits *beside* the contexts, is not one, may import every framework, and may
be imported by no context — a rule `tests/test_architecture.py` enforces. ADR-019 records the
reasoning: a composition root is genuinely the layer that wires things, so it belongs under a path
whose exemption it has earned.

## Decision

**The ledger gains `io.github.naimelijah.finassist.ledger.composition`, a composition root package
beside the contexts.** It holds the Spring configuration and every cross-context translation.

**The exemption is one-way, and both directions are enforced.**

1. `contexts_do_not_depend_on_each_other` gains
   `.ignoreDependency(resideInAPackage(COMPOSITION), alwaysTrue())` — the composition root may
   depend on anything.
2. A new rule, `no_context_depends_on_the_composition_root`, forbids the reverse. Without it the
   first rule would have opened a two-way door, and a context could reach the wiring that assembles
   it.

**A cross-context translation calls the upstream context through its in-port**, never its domain or
its repositories. `AccountingSpendJournal` calls `ViewJournalUseCase` and maps `JournalLine` to
budgeting's `SpendRecord`; `AccountingOpeningPositions` calls `ViewBalanceUseCase` and maps
planning's `AccountRef` to an `AccountId`. The in-port is the in-process equivalent of the
Published Language, which keeps the eventual extraction of a context a change of transport rather
than a change of design.

**`LedgerApplication` is no longer the only Spring-annotated class outside `adapter`.** Its Javadoc
said so and is corrected in the same change. `composition` is Spring-aware by design; the
application services and the domain remain annotation-free, now enforced by
`the_application_layer_depends_on_no_framework` (ADR-021).

All three rule changes were verified the way every rule in this repository has been: a violation
planted in a deep module, the build confirmed red, the violation reverted.

## Consequences

**Makes easy**

- The two services now have the same shape for the same idea, under the same name and with the
  same one-way exemption. A reader who has understood one has understood the other.
- Cross-context wiring has an obvious home, so the next one does not restart this argument.
- Extracting a context stays a `git mv` plus a change of transport: the translation is already
  written, already isolated, and already calls a published in-port rather than reaching inside.

**Makes hard**

- An exemption in an architecture rule is a hole, and a hole grows. `composition` can now import
  anything, so it is the one ledger package where coupling accumulates unchallenged — the same
  caveat ADR-010 records against `shared`, now applying to two packages instead of one.
- The rule that protects the exemption is itself only as good as its coverage. It forbids any class
  outside `composition` from depending on it; it cannot stop `composition` from growing logic that
  belongs in a context.

**Accepted cost**

- `composition` holds real mapping code, not just `@Bean` methods, so it is a package with
  behaviour and therefore with its own tests. Calling it "just wiring" would be inaccurate.

## Alternatives considered

**Flat classes in the root package**, beside `LedgerApplication`. Legal today with no rule change
at all, since `ROOT.(*)..` captures nothing there. Rejected on legibility: the root package would
accumulate unrelated wiring classes with no name saying what they are, and the fact that it works
would be an accident of the slice pattern rather than a decision anyone recorded. A future
maintainer tidying them into a subpackage would break the build for reasons nothing explains.

**A layer qualifier on the isolation rule**, permitting cross-context dependencies inside
`..adapter..`. Rejected: that is the hole the rule exists to close, and it would let any context
reach any other as long as the file sat in the right directory.

**Duplicating the needed accounting logic inside budgeting and planning.** Genuinely considered,
since ADR-007 accepts duplicated translation as the price of isolation. Rejected because this is
not translation — it is reading the other context's records, which is not duplicable without
duplicating the records.

**Events instead of a port**, with budgeting subscribing to accounting's transaction events and
maintaining its own spend view. A defensible design and materially larger: it needs delivery,
ordering, replay and a second store, and it answers a question about spend with an eventually
consistent projection. See [ADR-023](ADR-023-domain-events-in-the-ledger.md), which keeps events
in-process precisely so that this stays a future option rather than today's mechanism.

## Revisit when

- **A context is extracted into its own service.** Its translation moves out of `composition` and
  becomes an HTTP adapter behind the same port; this record should then say which translations
  remain in-process.
- **`composition` acquires a decision rather than a mapping** — a rule about which accounts are
  cash, say. That means an invariant is homeless and belongs in a context, and the wiring package
  has started to hold the domain the rules were meant to protect.
- **A third cross-context translation appears** that does not fit "call the in-port and map the
  result". Two is a pattern; three that disagree is a design.
