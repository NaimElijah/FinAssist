# ADR-020: The journal is the audit record, and it lands with persistence

- **Status:** Accepted, acted on by [ADR-023](ADR-023-domain-events-in-the-ledger.md), extended by
  [ADR-033](ADR-033-the-journal-records-who.md) and [ADR-066](ADR-066-the-store-holds-every-rule-the-domain-holds-across-rows.md), its persistence obligation resolved by
  [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md)
- **Date:** 2026-08-31

## Context

Auditing was raised alongside the observability work in ADR-019, and it turned out to be a
different kind of question with a different answer.

An audit trail answers "who changed what, when, and why" durably, after the fact, for someone who
was not there. Every word of that depends on storage. FinAssist has none: ADR-006 (PostgreSQL) is
still **Proposed**, `backend/ledger/pom.xml` deliberately carries no driver, there is no schema, no
repository and no application service. The requirements list ratifying ADR-006 as an open question
owned by @NaimElijah.

Writing an audit log in that situation means one of three things, and all three are worse than
waiting. Logging audit events to stdout conflates a durable record with diagnostics — ADR-019 makes
logs a stream a collector may or may not be attached to, which is the opposite of what an audit
trail promises. Holding events in memory produces a record that dies with the process. Choosing a
table now decides ADR-006 by implication, which the requirements explicitly reserve.

What already exists is more useful than any of those. **Double-entry bookkeeping is itself an audit
mechanism**, and ADR-011 committed to it: entries sum to zero and are checked in the constructor, a
balance is derived by summing entries rather than stored in a field that could be updated without
trace, and the transaction lifecycle is a sealed type where `Reconciled` declares no transition
method so it cannot be moved on. `Voided` already carries a **reason**, and every transition already
takes a **date** as an argument rather than reading a clock (ADR-011, and the rule the assistant's
architecture test enforces on its own side). The domain therefore already models *why* and *when* a
change happened. What it does not model is *who*, because there is no authentication — also an open
question, also unowned by this record.

## Decision

**The append-only journal is the audit record.** FinAssist does not grow a second, parallel audit
log that restates what the books already say. A transaction that is voided is not deleted; it
becomes `Voided` carrying its reason, and the entries that balanced it remain. This follows from
ADR-011 rather than adding to it, and it is the reason "derived, never stored" is a rule.

**Auditing is implemented in the same commit as the first repository.** The trigger is precise:
when persistence lands, the mapping must preserve the journal's append-only character —
specifically, no `UPDATE` or `DELETE` on a recorded transaction's entries, and lifecycle changes
recorded as new state rather than as an overwrite. That is a property of the schema and its
migrations, so it must be decided with them and not retrofitted.

**What must be captured, recorded now so it is not rediscovered later:**

| Field | Where it comes from | State today |
|---|---|---|
| What changed | The transaction and its entries | Modelled — `Transaction`, `Entry` |
| When | The date passed into every transition | Modelled — no domain function reads a clock |
| Why | `Voided`'s reason; the transition itself for the rest | Partly modelled — only voiding carries prose |
| Who | An authenticated principal | **Absent** — no authentication exists |

**"Who" is blocked on authentication, not on persistence.** In a single-owner system with one
actor, an actor column would record the same value on every row and teach a future reader that the
question had been answered. It is listed here so that the authentication decision, when it is made,
knows it owes this record an actor.

**Diagnostic logging is not auditing.** ADR-019's structured logs, traces and metrics exist to
explain how the system is behaving now. They are sampled, they are dropped when no collector is
configured, and they are not a record anyone may rely on after the fact. Nothing in this project
should cite a log line as evidence of a financial change.

## Consequences

**Easier.** Nothing is built that would have to be unbuilt. The requirement is written down with a
named trigger, so it is a scheduled obligation rather than a gap someone has to notice. The domain
work that auditing depends on — immutable entries, derived balances, a frozen `Reconciled` state, a
reason on `Voided`, an injected clock — is already done and already tested.

**Harder.** There is a window in which the system has observability and no audit trail, and the two
are easy to confuse from the outside. This record exists partly to make that confusion answerable:
the logs are diagnostics, the journal is the record, and the journal is not yet durable.

**Accepted cost.** "Who" will be absent from the first version of the audit trail even after
persistence lands, unless authentication is decided first. That is visible above rather than
discovered during implementation.

## Revisit when

- **ADR-006 is ratified** — auditing stops being deferred and becomes part of the schema design in
  the same commit.
- **Authentication is decided** — the "who" column becomes implementable, and this record should be
  extended rather than superseded.
- **A second writer to the ledger appears**, which would break the assumption that the journal alone
  is a sufficient record. ADR-004 currently makes the ledger the sole writer; if that changes, this
  decision changes with it.

## Alternatives considered

**A separate append-only audit table now.** Rejected on sequencing, not on merit — this is the
likely shape of the eventual implementation. Building it today would require choosing PostgreSQL,
which ADR-006 leaves open and the requirements reserve as a decision to be made deliberately.

**Emitting audit events to the log stream.** Rejected, and worth stating explicitly because it is
the cheap option that looks equivalent. ADR-019 defines logs as a stream that is dropped when no
collector is attached; an audit record that disappears when the collector is down is not an audit
record.

**Domain events in `accounting/domain/event/` now.** Considered seriously, since the package exists
as an empty placeholder and tactical DDD is sanctioned in the ledger by ADR-007. Rejected because
nothing would publish or consume them: with no application service there is no place that raises an
event and no subscriber to receive one, so the result would be types waiting for a caller. They
become the right answer at the same moment the application layer does.

**An in-memory `AuditEventRepository` behind Actuator's `auditevents` endpoint.** Rejected. Spring
Boot supplies this and it would have been nearly free, but its events are authentication and
authorisation events, of which this system currently produces none — the endpoint would expose an
empty list and imply a capability that does not exist. It also contradicts ADR-019's explicit
Actuator allow-list.

**Deciding nothing and leaving auditing unmentioned.** Rejected. The requirements document's own
rule is that a `TODO` is a decision waiting on its owner rather than an omission to be filled in by
inference. Auditing had not previously been named anywhere in `docs/`; leaving it that way would let
the next person conclude either that the journal already is the audit trail, or that a separate one
is needed, with nothing to arbitrate.
