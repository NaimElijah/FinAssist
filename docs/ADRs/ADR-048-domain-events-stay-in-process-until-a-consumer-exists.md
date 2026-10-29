# ADR-048: Domain events stay in-process until a consumer exists, and production keeps no event log

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-023](ADR-023-domain-events-in-the-ledger.md). It supersedes the sentence "The only
implementation is an append-only, readable log" (lines 49-52) and discharges the accepted cost
that sentence caused (lines 86-87). Everything else in ADR-023 stands: the application service
raises the events, the ports are `AccountingEvents` and `PlanningEvents`, and events are not the
audit trail.

## Context

**ADR-023's first revisit trigger fired, and nothing happened**
([#339](https://github.com/NaimElijah/FinAssist/issues/339)). It reads: "The first repository
lands. An outbox becomes real, `InMemoryAccountingEvents` is replaced, and the append-only
guarantee moves into the schema alongside ADR-020's journal obligation."

- [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md), dated 2026-09-06, chose the
  persistence. The first JDBC repository landed in `ab18bfc` on 2026-09-07.
- Nothing replaced the in-memory log. No outbox exists.

**Both in-memory logs were still the production beans, at `1d4f7ba`.**

- `LedgerConfiguration` wired `new InMemoryAccountingEvents()` and `new InMemoryPlanningEvents()`
  as the `AccountingEvents` and `PlanningEvents` beans of the running ledger.
- Each keeps every event in a `CopyOnWriteArrayList` and never truncates it.
- Their `published()` method is read by no production code. Four test classes read it:
  `AccountServiceTest`, `TransactionServiceTest`, `PlanServiceTest` and `ScenarioServiceTest`.

**ADR-023 accepted unbounded growth for "a development seam" only.** Its words are "The in-memory
log grows without bound in a long-running process. That is correct for a development seam and
wrong for a store." The ledger now runs as a Compose service under `unless-stopped`
([ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md)). Every accounting
and planning write it records adds an event to a list that nothing reads and a restart empties.

**A project instruction cited the wrong record for the outbox.** `backend/ledger/CLAUDE.md` said
an "outbox with no consumer is what ADR-020 refused to build ahead of one". ADR-020 rejected
domain events before an application layer existed, and a separate audit table on sequencing. The
deferred outbox is ADR-023's: it appears there as future work, at lines 17, 51, 67 and 110.

**The events still have a job.** ADR-023 records that a use-case test asserting what was raised
"caught two cases where a service performed a transition and forgot to persist it". Events are
not the audit trail ([ADR-020](ADR-020-audit-trail-follows-persistence.md), ADR-023 lines
54-56), so a ledger that discards them loses no record.

## Decision

**The application services keep raising every event, and the production publishers discard what
they are given.**

- **Services, ports and event types are unchanged.** `AccountService`, `TransactionService`,
  `PlanService` and `ScenarioService` still call `publish`. The sealed `AccountingEvent` and
  `PlanningEvent` interfaces stay. The tests keep pinning each transition's event.
- **The production bean accepts an event and keeps nothing.** `DiscardingAccountingEvents` and
  `DiscardingPlanningEvents`, one per port, in each context's `adapter/out/event` package and
  wired by `LedgerConfiguration`. Each still refuses a null event, as the recording double does,
  so a service that publishes one fails in production as it fails its tests.
- **The recording logs are test doubles.** `InMemoryAccountingEvents` and
  `InMemoryPlanningEvents` live in `src/test`, in the packages they had, so the four test classes
  that read `published()` kept working.

**The transactional outbox is built with the first consumer, in-process or out.** It lives in the
publishing context's own schema ([ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md)),
and is written in the same database transaction as the aggregate it announces. Until a consumer
exists, no table holds events.

## Consequences

**Makes easy**

- **The ledger's memory no longer grows with its writes.** No production code retains an event.
- **The seam stays.** Nothing about the port, the event types or the tests changes, so the first
  consumer arrives at a port that already works.
- **The project instruction is right.** The outbox is deferred by ADR-023 and this record, not
  refused by ADR-020, and `backend/ledger/CLAUDE.md` says so.

**Makes hard**

- **A running ledger shows no event.** Nothing lists what it raised. The tests do, and a failed
  `publish` call cannot be seen in production.
- **ADR-023's "outbox shape" argument loses its production incarnation.** The log that was
  "deliberately the shape an outbox has" now exists only in tests. Whether the shape suits a real
  outbox stays untested until one is built, as ADR-023 already said (lines 74-76).
- **A wiring mistake is silent.** A service handed the wrong publisher raises into nothing, and no
  production check notices. `DiscardingAccountingEventsTest` and `DiscardingPlanningEventsTest`
  pin the publishers, not the wiring.

**Accepted cost**

- **The events are still raised into nothing.** ADR-023 accepted that knowingly. This record
  removes the memory cost of it, not the oddity.

## Alternatives considered

- **Keep the in-memory log in production, and accept the growth under a new trigger.** Rejected:
  ADR-023 excused the growth as a "development seam", and the ledger is no longer one. A retained
  list that nothing reads is cost with no reader, and bounding it would need a policy, such as a
  ring buffer, that no consumer has asked for.
- **Build the outbox now, with no consumer.** Rejected, for ADR-023's own reason: "Events raised
  into nothing are decorative." A table of events nobody reads needs decisions about delivery,
  ordering, replay and retention that only a consumer can settle.
  [ADR-022](ADR-022-ledger-composition-root.md) counted the same costs against events as a
  mechanism: delivery, ordering, replay and a second store.
- **Stop raising events and delete the types.** Rejected: the owner rejected "keep deferring" in
  ADR-023, and the tests that pin each transition's event are what caught the forgotten
  persistence. Reopen it if no consumer appears (see below).

## Revisit when

- **The first consumer appears, in-process or out.** Build the outbox in the context's schema.
  This record's discarding publishers are replaced. ADR-023's second trigger, a second in-process
  consumer of a state change, is the same event.
- **A transition ships without its event.** ADR-023's last trigger carries over: the guarantee is
  too weak where it is, and moving it into the aggregate, or into a test that enumerates
  transitions, becomes the fix.
- **Someone needs to see the raised events of a running ledger.** A debugging need the tests
  cannot serve is a reason to retain a bounded log, or to build the outbox sooner.

## What lands with this record

- **Status line and README.** ADR-023's Status line and README row name this record and say it
  supersedes the in-memory-log sentence, and a note in `docs/ADRs/README.md` records the change.
- **A refactor in `backend/ledger`, behaviour-preserving for every caller.** The two discarding
  publishers and their tests, the two recording logs moved to `src/test`, and
  `backend/ledger/CLAUDE.md` citing ADR-023 and this record for the deferred outbox.
