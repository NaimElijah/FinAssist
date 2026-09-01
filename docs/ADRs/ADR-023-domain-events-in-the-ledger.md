# ADR-023: Domain events in the ledger, raised by the application service

- **Status:** Accepted
- **Date:** 2026-09-01

## Context

[ADR-020](ADR-020-audit-trail-follows-persistence.md) considered domain events and rejected them,
on a condition it stated precisely: "nothing would publish or consume them: with no application
service there is no place that raises an event and no subscriber to receive one, so the result
would be types waiting for a caller. **They become the right answer at the same moment the
application layer does.**"

[ADR-021](ADR-021-application-layer-shape.md) is that moment. This record acts on ADR-020's own
trigger rather than superseding it: the first half of the condition — no place that raises an
event — is now false. The second half is still true. There is no subscriber, and there will not be
one until persistence needs an outbox or a second consumer of a state change appears.

That asymmetry is the whole design problem here. Events raised into nothing are decorative, and
decorative code is indistinguishable from dead code within a release or two.

`accounting/domain/event/` has existed as an empty placeholder since the scaffold; `budgeting` and
`planning` never had one.

## Decision

**Event types live in the domain; the application service raises them.**
`Transaction` and `PlannedFlow` are records, and value semantics are load-bearing for both — the
test suites compare whole aggregates for equality, and `Scenario` refuses a duplicate `PlanId` by
comparing values. Hanging a mutable pending-events list off a record breaks its equality and its
immutability at once. So the aggregate stays a value, and the service that performed the
transition is what says it happened. The types themselves stay in `<context>/domain/event/`, which
is where ADR-007 puts them and where they can name domain vocabulary.

**Events are published through an out-port, like every other outward dependency.**
`AccountingEvents` and `PlanningEvents` sit in `application/port/out` beside the repositories. A
service holds the port, not a framework's publisher, so a use-case test asserts what was raised by
reading a fake — which is what makes the events exercised rather than assumed.

**Only `accounting` and `planning` raise events.** They are the two contexts with sealed lifecycles
and therefore the two with state transitions worth announcing. A `Budget` has no state machine: it
is defined, and then it is read. Giving it events for symmetry would be inventing a lifecycle to
justify a package.

**Each context has its own sealed event interface**, `AccountingEvent` and `PlanningEvent`, and
neither names the other. Sealing means a future subscriber gets an exhaustive `switch` and the
compiler tells it about a new event, rather than a default branch silently dropping one.

**The only implementation is an append-only, readable log.** `InMemoryAccountingEvents` and
`InMemoryPlanningEvents` keep what they were given, in order, and expose it for reading. They
delete nothing and overwrite nothing. This is deliberately the shape an outbox has, so that when
persistence lands the change is where the rows are written, not what the port looks like.

**Events are not the audit trail.** ADR-020 stands unchanged: the append-only journal is the audit
record, and an in-memory event log dies with the process exactly as a dropped log line does.
Nothing in this system may cite an event as evidence of a financial change.

**No domain events in the assistant.** ADR-007 confines tactical DDD to the ledger, and nothing in
the assistant has a lifecycle to announce.

## Consequences

**Makes easy**

- A use-case test can assert *what happened* rather than only what was stored, which caught two
  cases where a service performed a transition and forgot to persist it.
- The outbox ADR-020 will need has its port and its ordering guarantee already decided, so
  persistence adds a table rather than a design.
- A sealed hierarchy per context means the first subscriber is told by the compiler about every
  event it must handle.

**Makes hard**

- **This is the cost ADR-020 named, accepted knowingly.** Nothing consumes these events. They are
  raised, recorded, asserted in tests, and read by no production code, so their real fitness for
  a subscriber is untested and will stay untested until one exists.
- Five accounting event types and five planning ones are ten more types to keep in step with two
  lifecycles that already have sealed state interfaces. If a transition is added and its event is
  forgotten, only a test notices.
- Raising in the service rather than the aggregate means the aggregate cannot guarantee that a
  transition is always announced. The guarantee lives in one class per transition instead of in
  the type system.

**Accepted cost**

- The in-memory log grows without bound in a long-running process. That is correct for a
  development seam and wrong for a store, which is one more reason it is not one.

## Alternatives considered

**Keep deferring, per ADR-020 as written.** The conservative reading, and the one that ships no
unconsumed code. Rejected by the owner in favour of putting the shape in place while the services
that raise the events are being written, rather than retrofitting a raise site into every
transition later. The mitigations above exist because this alternative was the safer one.

**Events collected on the aggregate and drained by the service**, the classic tactical-DDD shape.
Rejected on a concrete conflict: the aggregates are records, equality is used throughout the
suites and by `Scenario`'s duplicate check, and a pending-events list breaks both.

**Spring's `ApplicationEventPublisher`.** Rejected outright — it would put `org.springframework`
in the application layer, which `the_application_layer_depends_on_no_framework` (ADR-021) now
fails the build for.

**One shared event hierarchy in `shared`.** Rejected for the reason ADR-010 gives about `shared`
generally: it is exempt from the isolation rule, so a common base type there would be a legal
route for one context's vocabulary to reach another.

## Revisit when

- **The first repository lands.** An outbox becomes real, `InMemoryAccountingEvents` is replaced,
  and the append-only guarantee moves into the schema alongside ADR-020's journal obligation.
- **A second in-process consumer of a state change appears** — budgeting maintaining a spend view,
  say, instead of asking for one through `SpendJournal` (ADR-022). That is the point at which
  these events stop being unconsumed and this record's accepted cost is discharged.
- **A projection must be maintained rather than derived.** "Derived, never stored" is a rule, and
  the first genuine exception to it is what events are usually reached for.
- **A transition ships without its event.** That means the guarantee is too weak where it is, and
  moving it into the aggregate — or into a test that enumerates transitions — becomes the fix.
